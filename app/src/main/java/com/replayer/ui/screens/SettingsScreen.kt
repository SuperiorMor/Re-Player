package com.replayer.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import com.replayer.data.ServerConfigManager
import com.replayer.model.UpnpDevice
import com.replayer.network.gdrive.GoogleDriveAuthManager
import com.replayer.network.gdrive.UserConsentRequiredException
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
    val isGdriveAuthenticated by GoogleDriveAuthManager.sharedAuthenticatedFlow.collectAsState()

    // UPnP scanning state
    var discoveredDevices by remember { mutableStateOf<List<UpnpDevice>>(emptyList()) }
    var isScanningUpnp by remember { mutableStateOf(false) }

    // Google Drive Sign-In state
    var isDriveSigningIn by remember { mutableStateOf(false) }
    var savedDriveEmail by remember { mutableStateOf(authManager.getSavedAccountEmail()) }
    var driveErrorMessage by remember { mutableStateOf<String?>(null) }
    var pendingAccountName by remember { mutableStateOf<String?>(null) }

    fun requestDriveToken(accountName: String) {
        isDriveSigningIn = true
        driveErrorMessage = null
        pendingAccountName = accountName
        scope.launch {
            val result = authManager.getAccessTokenForAccount(accountName)
            result.onSuccess {
                savedDriveEmail = accountName
                isDriveSigningIn = false
                driveErrorMessage = null
            }.onFailure { err ->
                if (err is UserConsentRequiredException && err.consentIntent != null) {
                    try {
                        consentLauncher.launch(err.consentIntent)
                    } catch (launchEx: Exception) {
                        isDriveSigningIn = false
                        driveErrorMessage = "Не удалось открыть подтверждение: ${launchEx.localizedMessage}"
                    }
                } else {
                    isDriveSigningIn = false
                    driveErrorMessage = err.localizedMessage ?: "Ошибка получения доступа Google"
                }
            }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = pendingAccountName
        if (account != null && (result.resultCode == Activity.RESULT_OK || result.resultCode == Activity.RESULT_CANCELED)) {
            requestDriveToken(account)
        } else {
            isDriveSigningIn = false
            driveErrorMessage = "Для подключения Google Диска необходимо нажать «Разрешить»"
        }
    }

    val chooseAccountLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!accountName.isNullOrBlank()) {
            requestDriveToken(accountName)
        } else {
            isDriveSigningIn = false
        }
    }

    fun startNativeGoogleSignIn() {
        driveErrorMessage = null
        isDriveSigningIn = true
        try {
            val chooseIntent = authManager.getChooseAccountIntent()
            chooseAccountLauncher.launch(chooseIntent)
        } catch (e: Exception) {
            isDriveSigningIn = false
            driveErrorMessage = "Не удалось открыть выбор аккаунта: ${e.localizedMessage}"
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
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isGdriveAuthenticated && !savedDriveEmail.isNullOrBlank()) {
                                    savedDriveEmail!!
                                } else {
                                    "Вход через аккаунт Google на ТВ — доступ ко всем вашим видео"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isGdriveAuthenticated && !savedDriveEmail.isNullOrBlank()) AccentCyan else TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isGdriveAuthenticated) {
                            TvFocusCard(
                                onClick = {
                                    authManager.clearAuth()
                                    savedDriveEmail = null
                                },
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "Отключить", color = Color(0xFFFF5252), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            TvFocusCard(
                                onClick = {
                                    if (!isDriveSigningIn) {
                                        startNativeGoogleSignIn()
                                    }
                                },
                                modifier = Modifier
                                    .width(200.dp)
                                    .height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (isDriveSigningIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = AccentCyan,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(
                                            text = "Войти через Google",
                                            color = AccentCyan,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
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
                                .width(140.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentCyan, strokeWidth = 2.dp)
                            }
                        }
                    }
                    is UpdateUiState.Available -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Orange "Что нового?" Button in Player Style
                            TvFocusCard(
                                onClick = {
                                    changelogRelease = state.release
                                    showChangelogDialog = true
                                },
                                focusBorderColor = Color(0xFFFF7A00),
                                backgroundColor = Color(0x28FF7A00),
                                modifier = Modifier
                                    .width(160.dp)
                                    .height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Что нового?",
                                        color = Color(0xFFFF9500),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }

                            TvFocusCard(
                                onClick = { startDownload(state.release) },
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "Скачать", color = AccentCyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    is UpdateUiState.Downloading -> {
                        TvFocusCard(
                            onClick = {},
                            modifier = Modifier
                                .width(130.dp)
                                .height(44.dp)
                                .focusRequester(updateActionFocusRequester)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = AccentCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${(state.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AccentCyan
                                )
                            }
                        }
                    }
                    is UpdateUiState.ReadyToInstall -> {
                        TvFocusCard(
                            onClick = { updateManager.installApk(state.apkFile) },
                            modifier = Modifier
                                .width(160.dp)
                                .height(44.dp)
                                .focusRequester(updateActionFocusRequester)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "Установить", color = AccentCyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    else -> {
                        TvFocusCard(
                            onClick = { startCheckUpdates() },
                            modifier = Modifier
                                .width(140.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (updateUiState is UpdateUiState.Error) "Повторить" else "Проверить",
                                    color = AccentCyan,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Dialog: "Что нового?" in Player Style
    // ---------------------------------------------------------
    if (showChangelogDialog && changelogRelease != null) {
        Dialog(onDismissRequest = { showChangelogDialog = false }) {
            Box(
                modifier = Modifier
                    .width(620.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkCard)
                    .border(2.dp, Color(0xFFFF7A00), RoundedCornerShape(20.dp))
                    .padding(28.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Re:",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
                                color = AccentCyan
                            )
                            Text(
                                text = "Player",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
                                color = Color(0xFFFF7A00)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x33FF7A00))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "v${changelogRelease!!.versionName}",
                                    color = Color(0xFFFF9500),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Главные улучшения этого обновления:",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    val items = getFriendlyChangelog(changelogRelease!!.versionName, changelogRelease!!.changelog)
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "•",
                                    color = Color(0xFFFF7A00),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(26.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TvFocusCard(
                            onClick = { showChangelogDialog = false },
                            focusBorderColor = Color(0xFFFF7A00),
                            backgroundColor = Color(0x33FF7A00),
                            modifier = Modifier
                                .width(140.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Понятно",
                                    color = Color(0xFFFF9500),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getFriendlyChangelog(version: String, rawChangelog: String): List<String> {
    if (version.contains("0.3.1")) {
        return listOf(
            "Нативный выбор аккаунта Android TV: Использование системного AccountManager вместо мобильного интерфейса — устранена ошибка 12500.",
            "Прямой запрос разрешений: Окно подтверждения доступа к Google Диску открывается на телевизоре без сбоев.",
            "Полный доступ к вашим видео: Чтение всех папок Google Диска с автообновлением токена."
        )
    }

    if (version.contains("0.3.0")) {
        return listOf(
            "Вход в Google Диск в 1 клик на ТВ: Нативная авторизация через аккаунт Google прямо на телевизоре без ввода кодов и телефона.",
            "Полный доступ ко всем файлам: Чтение всех ваших папок и видеоархивов на Google Диске благодаря правам drive.readonly.",
            "Мгновенное переподключение: Автоматическое фоновое обновление токена Google Play Services."
        )
    }

    if (version.contains("0.2.9")) {
        return listOf(
            "Исправление Client Secret: Скорректирован секретный ключ Google OAuth (устранена ошибка «client secret is invalid»).",
            "Очистка устаревших ключей: Автоматически удалены старые несоответствующие ключи от предыдущих версий.",
            "Надёжная авторизация: Вход через QR-код на телефоне теперь без ошибок передаёт токен на телевизор."
        )
    }

    if (version.contains("0.2.8")) {
        return listOf(
            "Google Drive: Вшиты новые параметры подключения (Desktop Client) с поддержкой прямого доступа ко всему диску.",
            "Фокус в настройках: Кнопка «Скачать» и «Установить» больше никогда не теряют фокус и не сбрасывают курсор в боковое меню.",
            "Воспроизведение AVI: Расчёт таймстемпов по точной спецификации AVI устраняет чёрный экран и рассинхрон звука."
        )
    }

    if (version.contains("0.2.7")) {
        return listOf(
            "Стабильное воспроизведение AVI: Полностью исправлена синхронизация аудио и видео — чистый звук и плавное видео без зависаний.",
            "Папка «Re:Player» на Google Диске: Автоматическое создание папки приложения для мгновенного доступа к вашим фильмам на ТВ.",
            "Выразительный логотип: Логотип в боковом меню стал крупнее и чётче, радуя глаз стильным неоновым свечением."
        )
    }

    if (version.contains("0.2.6")) {
        return listOf(
            "Стабильный фокус в настройках: При нажатии «Скачать» или «Проверить» фокус больше не улетает в меню на «Домой».",
            "Чистый звук и видео в AVI: Исправлен расчёт кадров и включён надёжный программный декодер MPEG-4 — никаких артефактов и инопланетных звуков.",
            "Загрузка файлов Google Drive: Исправлен запрос к Google API и добавлено автообновление токена — файлы и папки диска отображаются корректно."
        )
    }

    if (version.contains("0.2.5")) {
        return listOf(
            "Мгновенный вход в Google Диск: Вшит секретный ключ приложения. Больше никаких настроек API — просто сканируйте QR-код телефоном, и диск сразу подключится!",
            "Улучшенный QR-код: Код больше никогда не сбрасывается по ошибке, телевизор надёжно ожидает подтверждения с телефона.",
            "Плавное воспроизведение AVI и удобное управление: Все исправления плеера и кнопки «Назад» активны и работают по умолчанию."
        )
    }

    if (version.contains("0.2.4")) {
        return listOf(
            "Мгновенный «Назад»: Одно нажатие кнопки «Назад» сразу скрывает панель управления плеера без лишних кликов.",
            "Полный запуск AVI: Устранено зависание картинки — видео в формате .avi теперь непрерывно играет со звуком.",
            "Подключение Google Drive: Добавлена поддержка Client Secret, код больше не сбрасывается, а телевизор успешно авторизуется."
        )
    }

    if (version.contains("0.2.3")) {
        return listOf(
            "Память просмотренного: При выходе из видео курсор точно остаётся на этом файле.",
            "Стабильный фокус: При открытии и выходе из папок курсор больше не прыгает в боковое меню «Домой».",
            "Google Drive без ошибок: Устранена ошибка авторизации (400 invalid_scope), подключение проходит гладко.",
            "Восстановление AVI: Фильмы в формате .avi открываются и воспроизводятся стабильно с чистым звуком и картинкой."
        )
    }

    if (version.contains("0.2.2")) {
        return listOf(
            "Послушный курсор: При открытии папок и выходе назад фокус всегда на первом файле, а не улетает в меню.",
            "Кнопка «Назад»: Если панель плеера открыта, нажатие «Назад» просто скрывает её, не закрывая видео.",
            "Удобная перемотка: Стрелки влево и вправо мотают на 10 сек без лишних плашек, а полоса прогресса не прыгает в начало.",
            "Честный AVI: Фильмы в формате .avi теперь идут чисто и плавно — без шумов, заиканий и артефактов.",
            "Google Drive без заминок: После входа на телефоне телевизор сразу открывает все твои папки и файлы."
        )
    }

    if (version.contains("0.2.1")) {
        return listOf(
            "Фирменный логотип: Вместо скучного текста теперь аккуратный прозрачный логотип в боковом меню.",
            "Поддержка AVI: Старые и редкие видео в формате .avi теперь открываются и воспроизводятся без сбоев.",
            "Пульт не теряется: Меню плеера больше не застревает — нажми любую стрелку или ОК, и управление появится.",
            "Умная память папок: После просмотра возвращаешься ровно в ту же папку, и курсор сразу стоит на просмотренном файле.",
            "Быстрый выход: Одно нажатие «Назад» во время фильма сразу возвращает к списку файлов."
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
        pInfo.versionName ?: "0.1.3"
    } catch (e: Exception) {
        "0.1.3"
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
