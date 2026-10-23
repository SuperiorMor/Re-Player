package com.replayer.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.replayer.BuildConfig
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
    val updateManager = remember { UpdateManager(context) }
    val currentVersion = remember { getCurrentAppVersion(context) }

    var uiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = true) {
        onBack()
    }

    fun startCheckUpdates() {
        uiState = UpdateUiState.Checking
        scope.launch {
            val result = updateManager.checkForUpdates(currentVersion)
            uiState = when (result) {
                is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(result.release)
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.NoReleasesFound -> UpdateUiState.NoReleases
                is UpdateCheckResult.Error -> UpdateUiState.Error(result.message)
            }
        }
    }

    fun startDownload(release: ReleaseInfo) {
        uiState = UpdateUiState.Downloading(0f)
        downloadProgress = 0f
        scope.launch {
            val file = updateManager.downloadApk(release.downloadUrl) { progress ->
                downloadProgress = progress
                uiState = UpdateUiState.Downloading(progress)
            }
            if (file != null && file.exists()) {
                uiState = UpdateUiState.ReadyToInstall(file)
                updateManager.installApk(file)
            } else {
                uiState = UpdateUiState.Error("Не удалось загрузить файл обновления")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Настройки",
                    style = MaterialTheme.typography.displayLarge
                )
                Text(
                    text = "Параметры приложения и обновление системы",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // App Information Card
        TvFocusCard(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Re:Player",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentCyan)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v$currentVersion",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Современный медиаплеер для Android TV • DLNA / Google Drive / ExoPlayer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Update Section Title
        Text(
            text = "Обновление приложения",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Check Update Button Card
        TvFocusCard(
            onClick = {
                if (uiState !is UpdateUiState.Checking && uiState !is UpdateUiState.Downloading) {
                    startCheckUpdates()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(95.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Проверить обновление",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Проверка новых версий на GitHub и установка в один клик",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }

                if (uiState is UpdateUiState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = AccentCyan,
                        strokeWidth = 3.dp
                    )
                }
            }
        }

        // Dynamic Update Status
        when (val state = uiState) {
            is UpdateUiState.Checking -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Подключение к GitHub и проверка последнего релиза...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentCyan
                )
            }
            is UpdateUiState.UpToDate -> {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "У вас установлена самая актуальная версия (v$currentVersion)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
            is UpdateUiState.NoReleases -> {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Текущая версия актуальна (v$currentVersion)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                }
            }
            is UpdateUiState.Available -> {
                Spacer(modifier = Modifier.height(20.dp))
                Column(
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
                        Column {
                            Text(
                                text = "🎉 Доступно обновление: ${state.release.title}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AccentCyan
                            )
                            Text(
                                text = "Файл: ${state.release.fileName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }

                        TvFocusCard(
                            onClick = { startDownload(state.release) },
                            modifier = Modifier
                                .width(240.dp)
                                .height(56.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = AccentCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Скачать и обновить",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Список изменений:",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.release.changelog,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
            is UpdateUiState.Downloading -> {
                Spacer(modifier = Modifier.height(20.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard)
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Загрузка обновления: ${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = state.progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = AccentCyan,
                        trackColor = DarkSurface
                    )
                }
            }
            is UpdateUiState.ReadyToInstall -> {
                Spacer(modifier = Modifier.height(20.dp))
                TvFocusCard(
                    onClick = { updateManager.installApk(state.apkFile) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentCyan)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Файл загружен. Нажмите, чтобы установить обновление",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
            }
            is UpdateUiState.Error -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Ошибка: ${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFF5252)
                )
            }
            else -> {}
        }
    }
}

private fun getCurrentAppVersion(context: Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pInfo.versionName ?: "0.1.1"
    } catch (e: Exception) {
        "0.1.1"
    }
}
