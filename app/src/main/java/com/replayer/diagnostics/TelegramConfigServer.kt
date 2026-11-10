package com.replayer.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelegramConfigServer(
    private val onConfigUpdated: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var port: Int = 8765
        private set

    fun start(): String? {
        stop()
        val ip = findLocalIpv4Address() ?: return null
        val portsToTry = listOf(8765, 8766, 8767, 8768, 8789)
        var boundSocket: ServerSocket? = null
        for (p in portsToTry) {
            try {
                boundSocket = ServerSocket(p).apply {
                    reuseAddress = true
                }
                port = p
                break
            } catch (_: Throwable) {}
        }
        if (boundSocket == null) return null
        serverSocket = boundSocket

        acceptJob = scope.launch {
            while (isActive && !boundSocket.isClosed) {
                try {
                    val client = boundSocket.accept()
                    launch { handleClient(client) }
                } catch (_: Throwable) {
                    break
                }
            }
        }
        return "http://$ip:$port"
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            val headerBytes = readUntilHeaderEnd(input) ?: return
            val headerText = String(headerBytes, Charsets.ISO_8859_1)
            val headerLines = headerText.split("\r\n")
            if (headerLines.isEmpty()) return

            val reqParts = headerLines[0].split(" ")
            if (reqParts.size < 2) return
            val method = reqParts[0].uppercase(Locale.US)
            val rawTarget = reqParts[1]
            val path = rawTarget.substringBefore('?')
            val queryStr = rawTarget.substringAfter('?', "")

            val headers = HashMap<String, String>()
            for (i in 1 until headerLines.size) {
                val line = headerLines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 65536) ?: 0
            val bodyBytes = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
            val bodyText = String(bodyBytes, Charsets.UTF_8)

            when {
                method == "GET" && path == "/download" -> {
                    val params = parseFormParams(queryStr)
                    val fileName = params["name"]?.trim().orEmpty()
                    val file = CrashReporter.getReportFile(fileName)
                    if (file != null) {
                        val fileBytes = file.readBytes()
                        val safeName = file.name.replace("\"", "")
                        sendResponse(
                            output = output,
                            status = "200 OK",
                            contentType = "text/plain; charset=utf-8",
                            extraHeaders = listOf("Content-Disposition" to "attachment; filename=\"$safeName\""),
                            body = fileBytes
                        )
                    } else {
                        sendHtml(output, "404 Not Found", "<h3>Файл не найден</h3>")
                    }
                }

                method == "POST" && path == "/save" -> {
                    val form = parseFormParams(bodyText)
                    val token = form["token"]?.trim().orEmpty()
                    var chatId = form["chat_id"]?.trim().orEmpty()
                    val action = form["action"]?.trim().orEmpty()

                    var statusBanner: Pair<Boolean, String>
                    if (token.isBlank()) {
                        statusBanner = false to "Введите Bot Token от @BotFather."
                    } else {
                        if (chatId.isBlank() || action == "detect") {
                            val detectRes = runBlocking(Dispatchers.IO) {
                                CrashReporter.detectChatIdFromBot(token)
                            }
                            if (detectRes.isSuccess) {
                                val (foundId, foundName) = detectRes.getOrThrow()
                                chatId = foundId
                                CrashReporter.saveTelegramConfig(token, chatId)
                                val testRes = runBlocking(Dispatchers.IO) { CrashReporter.sendTestMessage() }
                                statusBanner = if (testRes.isSuccess) {
                                    true to "Chat ID найден ($foundName: $chatId) и тестовое сообщение отправлено!"
                                } else {
                                    false to "Chat ID найден ($chatId), но отправка не удалась: ${testRes.exceptionOrNull()?.message}"
                                }
                                onConfigUpdated()
                            } else {
                                CrashReporter.saveTelegramConfig(token, chatId)
                                onConfigUpdated()
                                statusBanner = false to (detectRes.exceptionOrNull()?.message ?: "Не удалось определить Chat ID")
                            }
                        } else {
                            CrashReporter.saveTelegramConfig(token, chatId)
                            onConfigUpdated()
                            val testRes = runBlocking(Dispatchers.IO) { CrashReporter.sendTestMessage() }
                            statusBanner = if (testRes.isSuccess) {
                                true to "Сохранено! Тестовое сообщение отправлено в Telegram."
                            } else {
                                false to "Сохранено, но ошибка отправки: ${testRes.exceptionOrNull()?.message}"
                            }
                        }
                    }

                    sendHtml(output, "200 OK", buildWebPageHtml(statusBanner))
                }

                method == "POST" && path == "/diag" -> {
                    val res = runBlocking(Dispatchers.IO) { CrashReporter.sendDiagnosticSnapshotNow() }
                    val banner = if (res.isSuccess) {
                        true to (res.getOrNull() ?: "Отправлено в Telegram!")
                    } else {
                        false to (res.exceptionOrNull()?.message ?: "Ошибка отправки")
                    }
                    onConfigUpdated()
                    sendHtml(output, "200 OK", buildWebPageHtml(banner))
                }

                else -> {
                    sendHtml(output, "200 OK", buildWebPageHtml(null))
                }
            }
        }
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        var state = 0
        var count = 0
        while (count < 16384) {
            val b = input.read()
            if (b == -1) return if (baos.size() > 0) baos.toByteArray() else null
            baos.write(b)
            count++
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (state == 4) return baos.toByteArray()
        }
        return baos.toByteArray()
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buf, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == length) buf else buf.copyOf(offset)
    }

    private fun parseFormParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val valRaw = if (eq >= 0) pair.substring(eq + 1) else ""
            try {
                map[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(valRaw, "UTF-8")
            } catch (_: Throwable) {}
        }
        return map
    }

    private fun escapeHtml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun buildWebPageHtml(statusBanner: Pair<Boolean, String>?): String {
        val currentToken = escapeHtml(CrashReporter.getBotToken())
        val currentChatId = escapeHtml(CrashReporter.getChatId())
        val reports = CrashReporter.reports.value
        val df = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

        val bannerHtml = if (statusBanner != null) {
            val bg = if (statusBanner.first) "#123828" else "#3B1920"
            val border = if (statusBanner.first) "#2EE59D" else "#FF5A79"
            "<div style='padding:12px 14px;border-radius:10px;background:$bg;border:1px solid $border;margin-bottom:16px;font-size:14px;'>${escapeHtml(statusBanner.second)}</div>"
        } else ""

        val reportsHtml = if (reports.isEmpty()) {
            "<p style='color:#94A3B8;font-size:14px;'>Сохранённых отчётов о сбоях пока нет.</p>"
        } else {
            reports.joinToString("") { r ->
                val dateLabel = df.format(Date(r.timestampMs))
                val badge = if (r.isSent) {
                    "<span style='color:#2EE59D;font-size:12px;'>Отправлен в ТГ</span>"
                } else {
                    "<span style='color:#FFB74D;font-size:12px;'>Ожидает отправки</span>"
                }
                val encodedName = URLEncoder.encode(r.fileName, "UTF-8")
                "<div style='padding:10px 12px;border-radius:10px;background:#1A202C;margin-bottom:8px;display:flex;justify-content:space-between;align-items:center;gap:12px;'>" +
                    "<div style='min-width:0;flex:1;'>" +
                    "<div style='font-weight:600;font-size:14px;'>${escapeHtml(r.title)} · $dateLabel · $badge</div>" +
                    "<div style='color:#94A3B8;font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;'>${escapeHtml(r.preview)}</div>" +
                    "</div>" +
                    "<a href='/download?name=$encodedName' style='color:#00E5FF;text-decoration:none;font-weight:600;font-size:13px;white-space:nowrap;'>Скачать .txt</a>" +
                    "</div>"
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>Re:Player — Telegram логи</title>
              <style>
                body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:#0D1117;color:#F1F5F9;margin:0;padding:18px;}
                .card{max-width:520px;margin:0 auto;background:#151B26;border:1px solid #263142;border-radius:16px;padding:20px;}
                h1{font-size:20px;margin:0 0 8px 0;color:#00E5FF;}
                p.hint{font-size:13px;color:#94A3B8;line-height:1.45;margin:0 0 16px 0;}
                label{display:block;font-size:13px;color:#CBD5E1;margin-bottom:6px;font-weight:600;}
                input{width:100%;box-sizing:border-box;padding:11px 12px;border-radius:10px;border:1px solid #334155;background:#0F141D;color:#F8FAFC;font-size:15px;margin-bottom:14px;}
                .row{display:flex;gap:10px;flex-wrap:wrap;}
                button{flex:1;min-width:140px;padding:12px 14px;border-radius:10px;border:none;font-size:14px;font-weight:700;cursor:pointer;}
                .primary{background:#00E5FF;color:#091017;}
                .secondary{background:#232C3B;color:#F8FAFC;border:1px solid #384457;}
                .section{margin-top:22px;padding-top:16px;border-top:1px solid #263142;}
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Re:Player · Логи в Telegram</h1>
                <p class="hint">
                  1. Создайте бота в <b>@BotFather</b> (команда <code>/newbot</code>) и вставьте токен ниже.<br/>
                  2. Откройте созданного бота в Telegram и нажмите <b>Start</b> (отправьте <code>/start</code>).<br/>
                  3. Оставьте Chat ID пустым и нажмите «Найти Chat ID и сохранить».
                </p>
                $bannerHtml
                <form method="POST" action="/save">
                  <label>Bot Token</label>
                  <input type="text" name="token" value="$currentToken" placeholder="123456789:AAF..." required />
                  <label>Chat ID (необязательно — определится сам)</label>
                  <input type="text" name="chat_id" value="$currentChatId" placeholder="Автоопределение после /start" />
                  <div class="row">
                    <button class="primary" type="submit" name="action" value="detect">Найти Chat ID и сохранить</button>
                    <button class="secondary" type="submit" name="action" value="save">Сохранить и тест</button>
                  </div>
                </form>

                <div class="section">
                  <form method="POST" action="/diag">
                    <button class="secondary" type="submit" style="width:100%;">Отправить текущий снимок логов сейчас</button>
                  </form>
                </div>

                <div class="section">
                  <label style="margin-bottom:10px;">Локальные отчёты о сбоях (${reports.size})</label>
                  $reportsHtml
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun sendHtml(output: java.io.OutputStream, status: String, html: String) {
        sendResponse(output, status, "text/html; charset=utf-8", emptyList(), html.toByteArray(Charsets.UTF_8))
    }

    private fun sendResponse(
        output: java.io.OutputStream,
        status: String,
        contentType: String,
        extraHeaders: List<Pair<String, String>>,
        body: ByteArray
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in extraHeaders) {
            sb.append("$k: $v\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun findLocalIpv4Address(): String? {
        return try {
            val candidates = mutableListOf<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase(Locale.US)
                val priority = when {
                    name.startsWith("eth") || name.startsWith("wlan") -> 0
                    name.startsWith("en") -> 1
                    name.contains("tun") || name.contains("p2p") || name.contains("rmnet") || name.contains("dummy") -> 9
                    else -> 3
                }
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(priority to host)
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        } catch (_: Throwable) {
            null
        }
    }
}
