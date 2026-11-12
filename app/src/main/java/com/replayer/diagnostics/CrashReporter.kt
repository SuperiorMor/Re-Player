package com.replayer.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import com.replayer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CrashReportInfo(
    val fileName: String,
    val title: String,
    val timestampMs: Long,
    val isSent: Boolean,
    val preview: String,
    val fullText: String
)

object CrashReporter {
    private const val PREFS_NAME = "replayer_diagnostics"
    private const val KEY_BOT_TOKEN = "tg_bot_token"
    private const val KEY_CHAT_ID = "tg_chat_id"
    private const val KEY_AUTO_SEND = "tg_auto_send"
    private const val KEY_LAST_EXIT_TS = "last_exit_check_ts"
    private const val MAX_BREADCRUMBS = 80
    private const val MAX_REPORTS_KEPT = 12

    private val initialized = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var reportsDir: File
    private lateinit var currentBreadcrumbsFile: File
    private lateinit var prevBreadcrumbsFile: File

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val breadcrumbLock = Any()
    private val breadcrumbs = ArrayDeque<String>(MAX_BREADCRUMBS)
    private val diskExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val _reports = MutableStateFlow<List<CrashReportInfo>>(emptyList())
    val reports: StateFlow<List<CrashReportInfo>> = _reports.asStateFlow()

    private val _lastSendStatus = MutableStateFlow<String?>(null)
    val lastSendStatus: StateFlow<String?> = _lastSendStatus.asStateFlow()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        reportsDir = File(appContext.filesDir, "crash_reports").apply { mkdirs() }
        val pid = Process.myPid()
        currentBreadcrumbsFile = File(reportsDir, "breadcrumbs_${pid}.log")
        prevBreadcrumbsFile = File(reportsDir, "prev_session_breadcrumbs.log")

        // Prune stale per-PID breadcrumb files (keep up to 6 newest within last 48h)
        try {
            val cutoff = System.currentTimeMillis() - 48L * 3600 * 1000L
            val pidLogs = reportsDir.listFiles { f ->
                f.isFile && f.name.startsWith("breadcrumbs_") && f.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
            pidLogs.forEachIndexed { idx, file ->
                if (idx >= 6 || file.lastModified() < cutoff) {
                    file.delete()
                }
            }
            if (currentBreadcrumbsFile.exists()) {
                currentBreadcrumbsFile.writeText("")
            }
        } catch (_: Throwable) {}

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }

        breadcrumb("App", "Start Re:Player v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")

        refreshReportsList()

        // Inspect historical OS exit reasons (Android 11+) and send pending crash reports in background
        scope.launch {
            checkHistoricalProcessExits()
            refreshReportsList()
            delay(3500)
            if (isAutoSendEnabled() && isConfigured()) {
                sendPendingReportsInternal()
            }
        }
    }

    fun hasBuiltInConfig(): Boolean {
        return BuildConfig.DEFAULT_TG_BOT_TOKEN.trim().isNotBlank() &&
            BuildConfig.DEFAULT_TG_CHAT_ID.trim().isNotBlank()
    }

    fun hasCustomOverride(): Boolean {
        if (!::prefs.isInitialized) return false
        return !prefs.getString(KEY_BOT_TOKEN, null).isNullOrBlank()
    }

    fun getCustomBotToken(): String {
        if (!::prefs.isInitialized) return ""
        return prefs.getString(KEY_BOT_TOKEN, null)?.trim().orEmpty()
    }

    fun getCustomChatId(): String {
        if (!::prefs.isInitialized) return ""
        return prefs.getString(KEY_CHAT_ID, null)?.trim().orEmpty()
    }

    fun getBotToken(): String {
        if (!::prefs.isInitialized) return BuildConfig.DEFAULT_TG_BOT_TOKEN.trim()
        val saved = prefs.getString(KEY_BOT_TOKEN, null)?.trim()
        return if (!saved.isNullOrEmpty()) saved else BuildConfig.DEFAULT_TG_BOT_TOKEN.trim()
    }

    fun getChatId(): String {
        if (!::prefs.isInitialized) return BuildConfig.DEFAULT_TG_CHAT_ID.trim()
        val saved = prefs.getString(KEY_CHAT_ID, null)?.trim()
        return if (!saved.isNullOrEmpty()) saved else BuildConfig.DEFAULT_TG_CHAT_ID.trim()
    }

    fun clearCustomConfig() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .remove(KEY_BOT_TOKEN)
            .remove(KEY_CHAT_ID)
            .commit()
    }

    fun isConfigured(): Boolean {
        return getBotToken().isNotBlank() && getChatId().isNotBlank()
    }

    fun isAutoSendEnabled(): Boolean {
        if (!::prefs.isInitialized) return true
        return prefs.getBoolean(KEY_AUTO_SEND, true)
    }

    fun setAutoSendEnabled(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_AUTO_SEND, enabled).apply()
    }

    fun saveTelegramConfig(botToken: String, chatId: String) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_BOT_TOKEN, botToken.trim())
            .putString(KEY_CHAT_ID, chatId.trim())
            .commit()
    }

    fun breadcrumb(tag: String, message: String) {
        if (!initialized.get()) return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts [$tag] ${message.replace('\n', ' ').take(300)}"
        val snapshotText: String
        synchronized(breadcrumbLock) {
            if (breadcrumbs.size >= MAX_BREADCRUMBS) {
                breadcrumbs.removeFirst()
            }
            breadcrumbs.addLast(line)
            snapshotText = breadcrumbs.joinToString("\n")
        }
        try {
            diskExecutor.execute {
                try {
                    currentBreadcrumbsFile.writeText(snapshotText)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        if (handlingCrash.compareAndSet(false, true)) {
            try {
                val now = System.currentTimeMillis()
                val report = buildReportText(
                    kind = "UNCAUGHT EXCEPTION",
                    timestampMs = now,
                    threadName = thread.name,
                    throwable = throwable,
                    extraDetails = null,
                    breadcrumbsOverride = null,
                    includeLogcat = true
                )
                writePendingReport(now, "crash", report)
            } catch (_: Throwable) {
            }
        }
        try {
            previousHandler?.uncaughtException(thread, throwable)
        } catch (_: Throwable) {
            Process.killProcess(Process.myPid())
        }
    }

    private fun checkHistoricalProcessExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val reasons = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 5)
            if (reasons.isEmpty()) return

            val lastCheckedTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
            var newestSeenTs = lastCheckedTs

            // Process oldest-to-newest so chronological order stays clean
            for (info in reasons.reversed()) {
                val ts = info.timestamp
                if (ts <= lastCheckedTs) continue
                if (ts > newestSeenTs) newestSeenTs = ts

                val reasonCode = info.reason
                val isUnexpected = reasonCode == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    reasonCode == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    reasonCode == ApplicationExitInfo.REASON_ANR ||
                    reasonCode == ApplicationExitInfo.REASON_CRASH ||
                    reasonCode == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE

                if (!isUnexpected) continue

                // Avoid duplicate report if UncaughtExceptionHandler already wrote one within +-15s
                val alreadyHaveJavaCrashNearTs = reportsDir.listFiles()?.any { f ->
                    val fTs = extractTimestampFromName(f.name)
                    fTs > 0L && kotlin.math.abs(fTs - ts) < 15_000L
                } == true

                if (reasonCode == ApplicationExitInfo.REASON_CRASH && alreadyHaveJavaCrashNearTs) {
                    continue
                }

                val reasonLabel = when (reasonCode) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH (LibVLC / C++)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "OUT OF MEMORY (Killed by Android OS)"
                    ApplicationExitInfo.REASON_ANR -> "ANR (UI Freeze)"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE RESOURCE USAGE"
                    ApplicationExitInfo.REASON_CRASH -> "PROCESS CRASH"
                    else -> "EXIT REASON #$reasonCode"
                }

                val traceSnippet = try {
                    info.traceInputStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val lines = mutableListOf<String>()
                        var count = 0
                        while (count < 250) {
                            val line = reader.readLine() ?: break
                            lines.add(line)
                            count++
                        }
                        lines.joinToString("\n").takeIf { it.isNotBlank() }
                    }
                } catch (_: Throwable) {
                    null
                }

                val details = buildString {
                    appendLine("Exit Reason: $reasonLabel ($reasonCode)")
                    appendLine("PID: ${info.pid} | Status: ${info.status} | Importance: ${info.importance}")
                    appendLine("PSS: ${info.pss} KB | RSS: ${info.rss} KB")
                    if (!info.description.isNullOrBlank()) {
                        appendLine("Description: ${info.description}")
                    }
                    if (!traceSnippet.isNullOrBlank()) {
                        appendLine("\n--- OS Trace ---")
                        appendLine(traceSnippet)
                    }
                }

                val pidCrumbsFile = File(reportsDir, "breadcrumbs_${info.pid}.log")
                val prevCrumbs = try {
                    when {
                        pidCrumbsFile.exists() -> pidCrumbsFile.readText()
                        prevBreadcrumbsFile.exists() -> prevBreadcrumbsFile.readText()
                        else -> null
                    }
                } catch (_: Throwable) {
                    null
                }

                val report = buildReportText(
                    kind = reasonLabel,
                    timestampMs = ts,
                    threadName = info.processName,
                    throwable = null,
                    extraDetails = details,
                    breadcrumbsOverride = prevCrumbs,
                    includeLogcat = false
                )
                writePendingReport(ts, "os_exit", report)
            }

            if (newestSeenTs > lastCheckedTs) {
                prefs.edit().putLong(KEY_LAST_EXIT_TS, newestSeenTs).apply()
            }
        } catch (_: Throwable) {}
    }

    private fun buildReportText(
        kind: String,
        timestampMs: Long,
        threadName: String?,
        throwable: Throwable?,
        extraDetails: String?,
        breadcrumbsOverride: String?,
        includeLogcat: Boolean
    ): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(timestampMs))
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)

        val memInfo = try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        } catch (_: Throwable) {
            null
        }

        val crumbText = breadcrumbsOverride ?: synchronized(breadcrumbLock) {
            breadcrumbs.joinToString("\n")
        }

        val logcatText = if (includeLogcat) readOwnLogcat(250) else ""

        val activeChannel = try {
            val prefs = appContext?.getSharedPreferences("replayer_prefs", Context.MODE_PRIVATE)
            val saved = prefs?.getString("update_channel", null)
            when (saved?.lowercase()) {
                "beta" -> "BETA"
                "stable" -> "STABLE"
                else -> if (BuildConfig.VERSION_NAME.contains("beta", ignoreCase = true)) "BETA" else "STABLE"
            }
        } catch (_: Throwable) {
            "UNKNOWN"
        }

        return buildString {
            appendLine("=== RE:PLAYER CRASH REPORT ===")
            appendLine("Type: $kind")
            appendLine("Time: $dateStr")
            appendLine("App: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, channel $activeChannel)")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            if (!threadName.isNullOrBlank()) appendLine("Thread: $threadName")
            appendLine("JVM Heap: ${usedMb}MB / ${maxMb}MB")
            if (memInfo != null) {
                val availMb = memInfo.availMem / (1024 * 1024)
                val totMb = memInfo.totalMem / (1024 * 1024)
                appendLine("System RAM: ${availMb}MB free / ${totMb}MB total (lowMemory=${memInfo.lowMemory})")
            }

            if (throwable != null) {
                appendLine("\n--- Stacktrace ---")
                appendLine(Log.getStackTraceString(throwable).trim())
            }

            if (!extraDetails.isNullOrBlank()) {
                appendLine("\n--- Details ---")
                appendLine(extraDetails.trim())
            }

            if (crumbText.isNotBlank()) {
                appendLine("\n--- Last Actions (Breadcrumbs) ---")
                appendLine(crumbText.trim())
            }

            if (logcatText.isNotBlank()) {
                appendLine("\n--- Logcat ---")
                appendLine(logcatText.trim())
            }
        }
    }

    private fun readOwnLogcat(lines: Int): String {
        return try {
            val pid = Process.myPid()
            val cmd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                arrayOf("logcat", "-d", "-t", lines.toString(), "--pid=$pid", "-v", "threadtime")
            } else {
                arrayOf("logcat", "-d", "-t", lines.toString(), "-v", "threadtime")
            }
            val proc = Runtime.getRuntime().exec(cmd)
            val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            try { proc.destroy() } catch (_: Throwable) {}
            text.takeLast(32000)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun writePendingReport(timestampMs: Long, prefix: String, content: String): File {
        val file = File(reportsDir, "${prefix}_${timestampMs}.pending.txt")
        file.writeText(content, Charsets.UTF_8)
        pruneOldReports()
        refreshReportsList()
        return file
    }

    private fun pruneOldReports() {
        try {
            val files = reportsDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".pending.txt") || f.name.endsWith(".sent.txt"))
            }?.sortedByDescending { extractTimestampFromName(it.name) } ?: return
            if (files.size > MAX_REPORTS_KEPT) {
                files.drop(MAX_REPORTS_KEPT).forEach { it.delete() }
            }
        } catch (_: Throwable) {}
    }

    private fun extractTimestampFromName(name: String): Long {
        val digits = name.substringAfter('_').substringBefore('.')
        return digits.toLongOrNull() ?: 0L
    }

    fun refreshReportsList() {
        if (!::reportsDir.isInitialized) return
        try {
            val files = reportsDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".pending.txt") || f.name.endsWith(".sent.txt"))
            }?.sortedByDescending { extractTimestampFromName(it.name) } ?: emptyList()

            _reports.value = files.map { file ->
                val text = try { file.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
                val lines = text.lines()
                val typeLine = lines.firstOrNull { it.startsWith("Type:") }?.removePrefix("Type:")?.trim() ?: "Crash Report"
                val previewLine = lines.firstOrNull {
                    it.contains("Exception") || it.contains("Error") || it.startsWith("Exit Reason:")
                }?.trim() ?: lines.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: ""

                CrashReportInfo(
                    fileName = file.name,
                    title = typeLine,
                    timestampMs = extractTimestampFromName(file.name).takeIf { it > 0L } ?: file.lastModified(),
                    isSent = file.name.endsWith(".sent.txt"),
                    preview = previewLine.take(140),
                    fullText = text
                )
            }
        } catch (_: Throwable) {}
    }

    fun getReportFile(fileName: String): File? {
        if (!::reportsDir.isInitialized) return null
        val safeName = File(fileName).name
        val target = File(reportsDir, safeName)
        return if (target.exists() && target.isFile) target else null
    }

    fun clearReports() {
        if (!::reportsDir.isInitialized) return
        try {
            reportsDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".pending.txt") || f.name.endsWith(".sent.txt"))
            }?.forEach { it.delete() }
            refreshReportsList()
        } catch (_: Throwable) {}
    }

    suspend fun detectChatIdFromBot(botToken: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cleanToken = botToken.trim()
        if (cleanToken.isBlank() || !cleanToken.contains(':')) {
            return@withContext Result.failure(IllegalArgumentException("Неверный формат токена бота"))
        }
        try {
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$cleanToken/getUpdates?limit=20")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val desc = try { JSONObject(body).optString("description") } catch (_: Throwable) { "" }
                    return@withContext Result.failure(IllegalStateException(desc.ifBlank { "HTTP ${resp.code}" }))
                }
                val root = JSONObject(body)
                if (!root.optBoolean("ok", false)) {
                    return@withContext Result.failure(IllegalStateException(root.optString("description", "Ошибка Telegram API")))
                }
                val updates = root.optJSONArray("result")
                if (updates == null || updates.length() == 0) {
                    return@withContext Result.failure(IllegalStateException("Сообщений боту ещё нет. Напишите боту /start в Telegram и повторите."))
                }
                // Pick the newest update that contains a chat object
                for (i in updates.length() - 1 downTo 0) {
                    val upd = updates.getJSONObject(i)
                    val msg = upd.optJSONObject("message")
                        ?: upd.optJSONObject("edited_message")
                        ?: upd.optJSONObject("channel_post")
                        ?: upd.optJSONObject("my_chat_member")
                    val chat = msg?.optJSONObject("chat") ?: continue
                    val id = chat.optLong("id", 0L)
                    if (id != 0L) {
                        val title = chat.optString("title").ifBlank {
                            listOfNotNull(
                                chat.optString("first_name").ifBlank { null },
                                chat.optString("last_name").ifBlank { null }
                            ).joinToString(" ").ifBlank {
                                chat.optString("username").ifBlank { "Chat $id" }
                            }
                        }
                        return@withContext Result.success(id.toString() to title)
                    }
                }
                Result.failure(IllegalStateException("Не удалось найти chat_id. Напишите боту /start в Telegram и повторите."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendTestMessage(): Result<String> = withContext(Dispatchers.IO) {
        val token = getBotToken()
        val chatId = getChatId()
        if (token.isBlank() || chatId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Сначала укажите Bot Token и Chat ID"))
        }
        val msg = "Re:Player v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) подключён к Telegram.\n" +
            "Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}).\n" +
            "Автоматический сбор логов вылетов активен."
        val ok = sendTelegramText(token, chatId, msg)
        if (ok.isSuccess) {
            _lastSendStatus.value = "Тестовое сообщение отправлено в Telegram"
            // Also flush any pending crash reports
            val sentCount = sendPendingReportsInternal()
            if (sentCount > 0) {
                _lastSendStatus.value = "Подключено! Отправлено ожидающих отчётов: $sentCount"
            }
            Result.success(_lastSendStatus.value ?: "Отправлено")
        } else {
            val err = ok.exceptionOrNull()?.message ?: "Ошибка отправки"
            _lastSendStatus.value = "Ошибка: $err"
            Result.failure(IllegalStateException(err))
        }
    }

    suspend fun sendDiagnosticSnapshotNow(): Result<String> = withContext(Dispatchers.IO) {
        val token = getBotToken()
        val chatId = getChatId()
        if (token.isBlank() || chatId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Сначала настройте Bot Token и Chat ID"))
        }
        val now = System.currentTimeMillis()
        val report = buildReportText(
            kind = "MANUAL DIAGNOSTIC SNAPSHOT",
            timestampMs = now,
            threadName = Thread.currentThread().name,
            throwable = null,
            extraDetails = "Manually triggered diagnostic snapshot from Settings.",
            breadcrumbsOverride = null,
            includeLogcat = true
        )
        val fileName = "replayer_diag_${now}.txt"
        val summary = buildSummaryCaption(report)
        val textRes = sendTelegramText(token, chatId, summary)
        val docRes = sendTelegramDocument(token, chatId, fileName, report, "Снимок диагностики Re:Player v${BuildConfig.VERSION_NAME}")
        if (docRes.isSuccess) {
            // Flush pending crash files too if any
            val flushed = sendPendingReportsInternal()
            val statusMsg = if (flushed > 0) {
                "Диагностика и $flushed отчёт(ов) о сбоях отправлены в Telegram"
            } else {
                "Диагностический лог отправлен в Telegram"
            }
            _lastSendStatus.value = statusMsg
            Result.success(statusMsg)
        } else {
            val err = docRes.exceptionOrNull()?.message ?: textRes.exceptionOrNull()?.message ?: "Сбой сети"
            _lastSendStatus.value = "Ошибка отправки: $err"
            Result.failure(IllegalStateException(err))
        }
    }

    suspend fun sendPendingReports(): Int = withContext(Dispatchers.IO) {
        sendPendingReportsInternal()
    }

    private fun sendPendingReportsInternal(): Int {
        if (!::reportsDir.isInitialized || !isConfigured()) return 0
        val token = getBotToken()
        val chatId = getChatId()
        val pendingFiles = reportsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".pending.txt")
        }?.sortedBy { extractTimestampFromName(it.name) } ?: return 0

        var sentCount = 0
        for (file in pendingFiles) {
            val fullText = try { file.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
            if (fullText.isBlank()) {
                file.delete()
                continue
            }
            val summary = buildSummaryCaption(fullText)
            val textRes = sendTelegramText(token, chatId, summary)
            val docRes = sendTelegramDocument(
                token = token,
                chatId = chatId,
                fileName = file.name.replace(".pending.txt", ".txt"),
                content = fullText,
                caption = "Полный лог сбоя Re:Player v${BuildConfig.VERSION_NAME}"
            )
            if (textRes.isSuccess || docRes.isSuccess) {
                val sentFile = File(reportsDir, file.name.replace(".pending.txt", ".sent.txt"))
                file.renameTo(sentFile)
                sentCount++
            }
        }
        if (sentCount > 0) {
            _lastSendStatus.value = "Отправлено отчётов в Telegram: $sentCount"
            refreshReportsList()
        }
        return sentCount
    }

    private fun buildSummaryCaption(fullReport: String): String {
        val lines = fullReport.lines()
        val header = lines.takeWhile { !it.startsWith("--- Logcat ---") && !it.startsWith("--- OS Trace ---") }
        // Keep device metadata + first ~22 lines of stacktrace/details + last 8 breadcrumbs
        val stackStart = header.indexOfFirst { it.startsWith("--- Stacktrace ---") || it.startsWith("--- Details ---") }
        val crumbsStart = header.indexOfFirst { it.startsWith("--- Last Actions") }

        val sb = StringBuilder()
        val metaEnd = if (stackStart >= 0) stackStart else header.size
        header.take(metaEnd).forEach { sb.appendLine(it) }

        if (stackStart >= 0) {
            val stackEnd = if (crumbsStart > stackStart) crumbsStart else header.size
            val stackSlice = header.subList(stackStart, minOf(stackEnd, stackStart + 25))
            sb.appendLine()
            stackSlice.forEach { sb.appendLine(it) }
        }

        if (crumbsStart >= 0) {
            val crumbLines = header.subList(crumbsStart + 1, header.size).filter { it.isNotBlank() }.takeLast(8)
            if (crumbLines.isNotEmpty()) {
                sb.appendLine("\n--- Последние действия ---")
                crumbLines.forEach { sb.appendLine(it) }
            }
        }

        return sb.toString().trim().take(3800)
    }

    private fun sendTelegramText(botToken: String, chatId: String, text: String): Result<Unit> {
        return try {
            val formBody = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text.take(4000))
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendMessage")
                .post(formBody)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(Unit)
                } else if ((resp.code == 401 || resp.code == 404) && hasCustomOverride() && hasBuiltInConfig() && botToken == getCustomBotToken()) {
                    // Auto-recover if user's old saved token was revoked and built-in token exists
                    clearCustomConfig()
                    sendTelegramText(getBotToken(), getChatId(), text)
                } else {
                    val body = resp.body?.string().orEmpty()
                    val desc = try { JSONObject(body).optString("description") } catch (_: Throwable) { "" }
                    Result.failure(IllegalStateException(desc.ifBlank { "HTTP ${resp.code}" }))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendTelegramDocument(
        token: String,
        chatId: String,
        fileName: String,
        content: String,
        caption: String
    ): Result<Unit> {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption.take(1000))
                .addFormDataPart(
                    "document",
                    fileName,
                    content.toRequestBody("text/plain; charset=utf-8".toMediaType())
                )
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendDocument")
                .post(body)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(Unit)
                } else if ((resp.code == 401 || resp.code == 404) && hasCustomOverride() && hasBuiltInConfig() && token == getCustomBotToken()) {
                    clearCustomConfig()
                    sendTelegramDocument(getBotToken(), getChatId(), fileName, content, caption)
                } else {
                    val respStr = resp.body?.string().orEmpty()
                    val desc = try { JSONObject(respStr).optString("description") } catch (_: Throwable) { "" }
                    Result.failure(IllegalStateException(desc.ifBlank { "HTTP ${resp.code}" }))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
