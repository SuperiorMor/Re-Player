package com.replayer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.replayer.diagnostics.CrashReportInfo
import com.replayer.diagnostics.CrashReporter
import com.replayer.diagnostics.TelegramConfigServer
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.DarkSurface
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsSection() {
    val scope = rememberCoroutineScope()
    val reports by CrashReporter.reports.collectAsState()
    val reporterStatus by CrashReporter.lastSendStatus.collectAsState()

    var configVersion by remember { mutableIntStateOf(0) }
    val isConfigured = remember(configVersion) { CrashReporter.isConfigured() }
    val hasCustom = remember(configVersion) { CrashReporter.hasCustomOverride() }
    val hasBuiltIn = remember(configVersion) { CrashReporter.hasBuiltInConfig() }
    val currentChatId = remember(configVersion) { CrashReporter.getCustomChatId() }
    var autoSendEnabled by remember(configVersion) { mutableStateOf(CrashReporter.isAutoSendEnabled()) }

    var isSendingNow by remember { mutableStateOf(false) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }

    var showTelegramSetupDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CrashReporter.refreshReportsList()
    }

    val pendingCount = reports.count { !it.isSent }

    Spacer(modifier = Modifier.height(28.dp))

    Text(
        text = "Диагностика и логи сбоев (Telegram)",
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
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                        imageVector = Icons.Outlined.BugReport,
                        contentDescription = null,
                        tint = if (isConfigured) AccentCyan else TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    hasCustom -> "Свой Telegram подключён"
                                    hasBuiltIn -> "Телеметрия сбоев активна"
                                    isConfigured -> "Telegram подключён"
                                    else -> "Telegram не настроен"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isConfigured) Color(0xFF4ADE80) else TextPrimary
                            )
                            if (hasCustom && currentChatId.isNotBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ID: $currentChatId",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            } else if (!hasCustom && hasBuiltIn) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "встроено",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        val reportsSummary = when {
                            reports.isEmpty() -> "Зафиксированных вылетов пока нет"
                            pendingCount > 0 -> "Отчётов: ${reports.size} (ожидают отправки: $pendingCount)"
                            else -> "Отчётов о сбоях: ${reports.size} (все отправлены)"
                        }
                        Text(
                            text = actionFeedback ?: reporterStatus ?: reportsSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if ((actionFeedback ?: "").startsWith("Ошибка")) Color(0xFFFF6B6B) else TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Auto-send toggle
                TvFocusCard(
                    onClick = {
                        val next = !autoSendEnabled
                        autoSendEnabled = next
                        CrashReporter.setAutoSendEnabled(next)
                    },
                    modifier = Modifier
                        .width(175.dp)
                        .height(42.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (autoSendEnabled) "Автоотправка: ВКЛ" else "Автоотправка: ВЫКЛ",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (autoSendEnabled) AccentCyan else TextSecondary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvFocusCard(
                    onClick = { showTelegramSetupDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isSecondaryStyle = hasBuiltIn && !hasCustom
                        Icon(
                            imageVector = Icons.Outlined.QrCode2,
                            contentDescription = null,
                            tint = if (isSecondaryStyle) TextSecondary else AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSecondaryStyle) "Свой бот (QR)" else "Настроить (QR / бот)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }

                TvFocusCard(
                    onClick = {
                        if (!isSendingNow) {
                            isSendingNow = true
                            actionFeedback = "Отправка логов в Telegram..."
                            scope.launch {
                                val res = CrashReporter.sendDiagnosticSnapshotNow()
                                actionFeedback = res.getOrElse { "Ошибка: ${it.message}" }
                                isSendingNow = false
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSendingNow) {
                            CircularProgressIndicator(
                                color = AccentCyan,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Outlined.Send, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSendingNow) "Отправка..." else "Отправить логи сейчас",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }

                TvFocusCard(
                    onClick = {
                        CrashReporter.refreshReportsList()
                        showLogsDialog = true
                    },
                    modifier = Modifier
                        .width(175.dp)
                        .height(44.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ListAlt, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Журнал (${reports.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    if (showTelegramSetupDialog) {
        TelegramSetupDialog(
            onDismiss = { showTelegramSetupDialog = false },
            onConfigChanged = {
                configVersion++
                actionFeedback = CrashReporter.lastSendStatus.value ?: "Настройки Telegram обновлены"
            }
        )
    }

    if (showLogsDialog) {
        CrashLogsViewerDialog(
            reports = reports,
            onClear = {
                CrashReporter.clearReports()
                actionFeedback = "Журнал сбоев очищен"
            },
            onDismiss = { showLogsDialog = false }
        )
    }
}

@Composable
private fun TelegramSetupDialog(
    onDismiss: () -> Unit,
    onConfigChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var serverFailed by remember { mutableStateOf(false) }
    var manualMode by remember { mutableStateOf(false) }

    var tokenInput by remember { mutableStateOf(CrashReporter.getCustomBotToken()) }
    var chatIdInput by remember { mutableStateOf(CrashReporter.getCustomChatId()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    val server = remember {
        TelegramConfigServer(
            onConfigUpdated = {
                scope.launch(Dispatchers.Main) {
                    tokenInput = CrashReporter.getCustomBotToken()
                    chatIdInput = CrashReporter.getCustomChatId()
                    statusText = "Конфигурация обновлена с телефона!"
                    onConfigChanged()
                }
            }
        )
    }

    DisposableEffect(Unit) {
        val startedUrl = server.start()
        if (startedUrl != null) {
            serverUrl = startedUrl
        } else {
            serverFailed = true
        }
        onDispose {
            server.stop()
        }
    }

    BackHandler(enabled = true) {
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(680.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (manualMode) "Настройка Telegram вручную" else "Настройка Telegram с телефона",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    TvFocusCard(
                        onClick = { manualMode = !manualMode },
                        modifier = Modifier
                            .width(190.dp)
                            .height(38.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (manualMode) Icons.Outlined.QrCode2 else Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (manualMode) "Показать QR-код" else "Ввести с пульта",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }
                }

                if (!manualMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (serverUrl != null) {
                            Box(
                                modifier = Modifier
                                    .size(196.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                QrCodeCanvas(
                                    content = serverUrl!!,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "1. Создайте бота в @BotFather (команда /newbot) и скопируйте токен.\n" +
                                    "2. Нажмите /start у вашего бота в Telegram.\n" +
                                    "3. Откройте этот адрес на телефоне (в той же Wi-Fi сети):",
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                color = TextSecondary
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkSurface)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = serverUrl ?: if (serverFailed) "Нет локальной Wi-Fi/LAN сети — используйте ручной ввод" else "Запуск...",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = AccentCyan
                                )
                            }
                            Text(
                                text = "На открывшейся странице вставьте токен и нажмите «Найти Chat ID и сохранить». Там же можно скачать логи.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("Bot Token (@BotFather)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                        OutlinedTextField(
                            value = chatIdInput,
                            onValueChange = { chatIdInput = it },
                            label = { Text("Chat ID (или оставьте пустым и нажмите поиск)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvFocusCard(
                                onClick = {
                                    if (!isBusy) {
                                        isBusy = true
                                        statusText = "Поиск Chat ID по /start..."
                                        scope.launch {
                                            val res = CrashReporter.detectChatIdFromBot(tokenInput)
                                            if (res.isSuccess) {
                                                val (foundId, name) = res.getOrThrow()
                                                chatIdInput = foundId
                                                CrashReporter.saveTelegramConfig(tokenInput, foundId)
                                                onConfigChanged()
                                                statusText = "Найден чат: $name ($foundId)"
                                            } else {
                                                statusText = res.exceptionOrNull()?.message ?: "Не найдено"
                                            }
                                            isBusy = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Search, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Найти Chat ID", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                }
                            }

                            TvFocusCard(
                                onClick = {
                                    if (!isBusy) {
                                        isBusy = true
                                        CrashReporter.saveTelegramConfig(tokenInput, chatIdInput)
                                        onConfigChanged()
                                        statusText = "Проверка отправки..."
                                        scope.launch {
                                            val res = CrashReporter.sendTestMessage()
                                            statusText = res.getOrElse { "Ошибка: ${it.message}" }
                                            onConfigChanged()
                                            isBusy = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Сохранить и тест", style = MaterialTheme.typography.bodyMedium, color = AccentCyan, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusText?.startsWith("Ошибка") == true) AccentOrange else AccentCyanBright
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TvFocusCard(
                        onClick = onDismiss,
                        modifier = Modifier
                            .width(150.dp)
                            .height(42.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Закрыть", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeCanvas(
    content: String,
    modifier: Modifier = Modifier
) {
    val matrix = remember(content) {
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (_: Throwable) {
            null
        }
    }

    if (matrix != null) {
        Canvas(modifier = modifier) {
            val cols = matrix.width
            val rows = matrix.height
            if (cols > 0 && rows > 0) {
                val cellW = size.width / cols.toFloat()
                val cellH = size.height / rows.toFloat()
                for (y in 0 until rows) {
                    for (x in 0 until cols) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashLogsViewerDialog(
    reports: List<CrashReportInfo>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedReport by remember(reports) { mutableStateOf(reports.firstOrNull()) }
    val df = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }

    BackHandler(enabled = true) {
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Журнал сбоев (${reports.size})",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (reports.isNotEmpty()) {
                            TvFocusCard(
                                onClick = onClear,
                                modifier = Modifier
                                    .width(135.dp)
                                    .height(38.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Очистить", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                                }
                            }
                        }
                        TvFocusCard(
                            onClick = onDismiss,
                            modifier = Modifier
                                .width(120.dp)
                                .height(38.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Закрыть", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (reports.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Логи сбоев пусты — критических ошибок пока не зафиксировано.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Left: list of crash reports
                        LazyColumn(
                            modifier = Modifier
                                .width(270.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(reports, key = { it.fileName }) { report ->
                                val isSelected = selectedReport?.fileName == report.fileName
                                TvFocusCard(
                                    onClick = { selectedReport = report },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) AccentCyan.copy(alpha = 0.14f) else Color.Transparent)
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = report.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (isSelected) AccentCyan else TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${df.format(Date(report.timestampMs))} · ${if (report.isSent) "Отправлен" else "Ожидает"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (report.isSent) Color(0xFF4ADE80) else Color(0xFFFFB74D)
                                        )
                                        if (report.preview.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = report.preview,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Right: focus-scrollable chunks of selected report for easy TV D-pad reading
                        val active = selectedReport
                        val chunks = remember(active?.fileName) {
                            (active?.fullText ?: "")
                                .lines()
                                .chunked(14)
                                .map { it.joinToString("\n") }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurface)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(chunks) { _, chunkText ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusable()
                                        .padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = chunkText,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        ),
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
}
