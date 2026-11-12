package com.replayer.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.replayer.diagnostics.CrashReportInfo
import com.replayer.diagnostics.CrashReporter
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.AccentGold
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.DarkSurface
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsSection(
    leftFocusRequester: FocusRequester? = null
) {
    val scope = rememberCoroutineScope()
    val reports by CrashReporter.reports.collectAsState()

    var isSendingNow by remember { mutableStateOf(false) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var showLogsDialog by remember { mutableStateOf(false) }
    val journalFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        CrashReporter.refreshReportsList()
    }

    val totalCount = reports.size
    val sentCount = reports.count { it.isSent }
    val pendingCount = totalCount - sentCount

    Spacer(modifier = Modifier.height(28.dp))

    Text(
        text = "Диагностика и сбои",
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
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = buildString {
                            append("Зафиксировано сбоев: $totalCount • Отправлено: $sentCount")
                            if (pendingCount > 0) append(" (ожидают: $pendingCount)")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = actionFeedback ?: "Логи сбоев автоматически отправляются разработчику для исправления ошибок",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            (actionFeedback ?: "").startsWith("Ошибка") -> AccentOrange
                            actionFeedback != null -> AccentCyanBright
                            else -> TextSecondary
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvFocusCard(
                    onClick = {
                        if (!isSendingNow) {
                            isSendingNow = true
                            actionFeedback = "Отправка логов..."
                            scope.launch {
                                val res = CrashReporter.sendDiagnosticSnapshotNow()
                                CrashReporter.refreshReportsList()
                                actionFeedback = res.getOrElse { "Ошибка: ${it.message}" }
                                isSendingNow = false
                            }
                        }
                    },
                    modifier = Modifier
                        .width(225.dp)
                        .height(44.dp)
                        .then(
                            if (leftFocusRequester != null) {
                                Modifier.focusProperties { left = leftFocusRequester }
                            } else Modifier
                        )
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
                        .width(155.dp)
                        .height(44.dp)
                        .focusRequester(journalFocusRequester)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ListAlt, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Журнал ($totalCount)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
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
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xCC151B26))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                AccentCyan.copy(alpha = 0.10f),
                                Color.Transparent,
                                AccentOrange.copy(alpha = 0.06f)
                            )
                        )
                    )
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(20.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        } else Modifier
                    )
            )
            Column(modifier = Modifier.fillMaxSize().padding(22.dp)) {
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
                                            color = if (report.isSent) AccentCyanBright else AccentGold
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
