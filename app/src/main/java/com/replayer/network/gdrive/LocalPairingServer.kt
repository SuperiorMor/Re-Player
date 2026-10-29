package com.replayer.network.gdrive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID

class LocalPairingServer(
    private val context: Context,
    private val authManager: GoogleDriveAuthManager,
    private val onPaired: (String?) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var pollJob: Job? = null
    private val okClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    val sessionId: String = UUID.randomUUID().toString().take(8).lowercase()
    var localIp: String = "127.0.0.1"
        private set
    var port: Int = 8765
        private set

    val pairingUrl: String
        get() = "http://$localIp:$port/?session=$sessionId"

    fun start() {
        if (isRunning) return
        isRunning = true
        localIp = getLocalIpAddress()

        // 1. Локальный TCP HTTP сервер
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
            } catch (_: Exception) {
                port = 8766
                serverSocket = try { ServerSocket(port) } catch (_: Exception) { null }
            }

            Log.d("PairingServer", "Started on $pairingUrl")
            while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    launch { handleClient(clientSocket) }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }

        // 2. Cloud relay polling via ntfy.sh (fallback для LTE)
        pollJob = scope.launch {
            val topicUrl = "https://ntfy.sh/replayer_pair_$sessionId/raw?poll=1"
            var attempts = 0
            while (isActive && isRunning && attempts < 120) {
                delay(3000)
                attempts++
                try {
                    val request = Request.Builder().url(topicUrl).build()
                    val response = okClient.newCall(request).execute()
                    val body = response.body?.string()?.trim() ?: continue
                    if (body.isNotBlank() && body.contains("{") && body.contains("}")) {
                        Log.d("PairingServer", "Received ntfy payload: $body")
                        handleIncomingTokensJson(body)
                        break
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        pollJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val path = parts[1]

            // Считываем заголовки для Content-Length
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.lowercase().startsWith("content-length:")) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            when {
                method.equals("POST", ignoreCase = true) && path.startsWith("/api/save") -> {
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val read = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (read == -1) break
                        readTotal += read
                    }
                    val success = handleIncomingPayload(String(bodyChars))
                    sendJson(
                        out,
                        if (success) 200 else 400,
                        if (success) """{"status":"ok","message":"Google Drive успешно подключен на ТВ!"}"""
                        else """{"status":"error","message":"Не удалось сохранить токен"}"""
                    )
                }
                method.equals("POST", ignoreCase = true) && path.startsWith("/api/token") -> {
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val read = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (read == -1) break
                        readTotal += read
                    }
                    val success = handleIncomingTokensJson(String(bodyChars))
                    sendJson(
                        out,
                        if (success) 200 else 400,
                        if (success) """{"status":"ok"}""" else """{"status":"error"}"""
                    )
                }
                else -> {
                    // Serve mobile pairing web page
                    val html = getPairingHtml(
                        sessionId,
                        authManager.getSavedClientId(),
                        authManager.getSavedClientSecret()
                    )
                    sendHttpResponse(out, 200, "text/html; charset=utf-8", html)
                }
            }
        } catch (e: Exception) {
            Log.w("PairingServer", "Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleIncomingPayload(rawBody: String): Boolean {
        return try {
            if (rawBody.trim().startsWith("{")) {
                handleIncomingTokensJson(rawBody)
            } else {
                // URL-encoded form data
                val params = rawBody.split("&").associate { param ->
                    val pair = param.split("=")
                    val key = URLDecoder.decode(pair[0], "UTF-8")
                    val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                    key to value
                }
                val clientId = params["client_id"]?.trim() ?: ""
                val clientSecret = params["client_secret"]?.trim() ?: ""
                val refreshToken = params["refresh_token"]?.trim() ?: ""
                val accessToken = params["access_token"]?.trim() ?: ""

                if (clientId.isNotBlank()) authManager.saveClientId(clientId)
                if (clientSecret.isNotBlank()) authManager.saveClientSecret(clientSecret)

                if (accessToken.isNotBlank() || refreshToken.isNotBlank()) {
                    authManager.saveTokens(
                        accessToken = accessToken.ifBlank { "valid_via_refresh" },
                        refreshToken = refreshToken.ifBlank { null },
                        expiresInSec = 3600
                    )
                    scope.launch {
                        if (refreshToken.isNotBlank()) {
                            authManager.refreshAccessToken()
                        }
                        withContext(Dispatchers.Main) {
                            onPaired(authManager.getSavedAccountEmail())
                        }
                    }
                    return true
                }
                false
            }
        } catch (e: Exception) {
            Log.e("PairingServer", "Failed to parse payload: ${e.message}")
            false
        }
    }

    private fun handleIncomingTokensJson(jsonStr: String): Boolean {
        return try {
            val element = json.parseToJsonElement(jsonStr) as? JsonObject ?: return false
            val clientId = element["client_id"]?.jsonPrimitive?.contentOrNull?.trim()
            val clientSecret = element["client_secret"]?.jsonPrimitive?.contentOrNull?.trim()
            val accessToken = element["access_token"]?.jsonPrimitive?.contentOrNull?.trim()
            val refreshToken = element["refresh_token"]?.jsonPrimitive?.contentOrNull?.trim()
            val authCode = element["auth_code"]?.jsonPrimitive?.contentOrNull?.trim()

            if (!clientId.isNullOrBlank()) authManager.saveClientId(clientId)
            if (!clientSecret.isNullOrBlank()) authManager.saveClientSecret(clientSecret)

            if (!authCode.isNullOrBlank()) {
                scope.launch {
                    val result = authManager.exchangeAuthCode(
                        authCode,
                        clientId ?: authManager.getSavedClientId(),
                        clientSecret ?: authManager.getSavedClientSecret()
                    )
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            onPaired(authManager.getSavedAccountEmail())
                        }
                    }
                }
                return true
            }

            if (!accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank()) {
                authManager.saveTokens(
                    accessToken = accessToken ?: "valid_via_refresh",
                    refreshToken = refreshToken,
                    expiresInSec = 3600
                )
                scope.launch {
                    if (!refreshToken.isNullOrBlank()) {
                        authManager.refreshAccessToken()
                    }
                    withContext(Dispatchers.Main) {
                        onPaired(authManager.getSavedAccountEmail())
                    }
                }
                return true
            }
            false
        } catch (e: Exception) {
            Log.e("PairingServer", "handleIncomingTokensJson error: ${e.message}")
            false
        }
    }

    private fun sendJson(out: OutputStream, code: Int, body: String) {
        sendHttpResponse(out, code, "application/json", body)
    }

    private fun sendHttpResponse(out: OutputStream, code: Int, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val statusText = if (code == 200) "OK" else "Bad Request"
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    /**
     * Pairing-страница. OOB (urn:ietf:wg:oauth:2.0:oob) убран — Google закрыл его в 2022.
     *
     * Основной путь: запустить tools/auth_gdrive.py на компе (он сам проводит OAuth
     * через редирект на localhost и выдаёт готовый Refresh Token), затем скопировать
     * Client ID + Client Secret + Refresh Token на эту страницу и отправить на ТВ.
     *
     * Важно: Refresh Token привязан к тому Client ID, который его выдал.
     * Поэтому Client ID/Secret с этой страницы ОБЯЗАТЕЛЬНО должны совпадать
     * с теми, что использовались в auth_gdrive.py.
     */
    private fun getPairingHtml(session: String, initialClientId: String, initialClientSecret: String): String {
        return """
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Re:Player — Подключение Google Drive</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: #090A0E;
            color: #F8FAFC;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            padding: 20px;
        }
        .card {
            background-color: #13161F;
            border: 1px solid rgba(6, 182, 212, 0.25);
            border-radius: 16px;
            max-width: 560px;
            width: 100%;
            padding: 28px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.6);
        }
        .logo { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
        .logo-icon {
            width: 36px; height: 36px;
            background: linear-gradient(135deg, #06B6D4, #38BDF8);
            border-radius: 8px;
            display: flex; align-items: center; justify-content: center;
            font-weight: bold; color: #090A0E; font-size: 18px;
        }
        h1 { font-size: 20px; font-weight: 700; color: #F8FAFC; }
        .subtitle { font-size: 14px; color: #94A3B8; margin-bottom: 20px; line-height: 1.45; }
        .status-badge {
            background: rgba(6, 182, 212, 0.15);
            color: #06B6D4;
            padding: 6px 12px; border-radius: 8px;
            font-size: 13px; margin-bottom: 20px;
            display: inline-block;
        }
        label { display: block; font-size: 13px; font-weight: 600; color: #CBD5E1; margin-bottom: 6px; }
        input[type="text"] {
            width: 100%;
            background: #1A1E2B;
            border: 1px solid #334155;
            color: #F8FAFC;
            padding: 12px 14px;
            border-radius: 8px;
            font-size: 14px;
            margin-bottom: 16px;
            outline: none;
            transition: border-color 0.2s;
        }
        input[type="text"]:focus { border-color: #06B6D4; }
        .btn {
            display: block; width: 100%;
            background: linear-gradient(135deg, #06B6D4, #0284C7);
            color: #090A0E; font-weight: 700; font-size: 15px;
            padding: 14px; border-radius: 10px;
            border: none; cursor: pointer;
            text-align: center; text-decoration: none;
            margin-top: 8px; margin-bottom: 16px;
            transition: opacity 0.2s;
        }
        .btn:hover { opacity: 0.9; }
        .info-box {
            background: rgba(30, 41, 59, 0.5);
            border-left: 3px solid #06B6D4;
            padding: 12px 14px;
            border-radius: 0 8px 8px 0;
            font-size: 13px;
            color: #94A3B8;
            margin-bottom: 18px;
            line-height: 1.55;
        }
        .info-box b { color: #E2E8F0; }
        .info-box code {
            background: rgba(6,182,212,0.12);
            color: #06B6D4;
            padding: 1px 6px;
            border-radius: 4px;
            font-family: 'Courier New', monospace;
        }
        .success-box {
            display: none;
            background: rgba(16, 185, 129, 0.15);
            border: 1px solid #10B981;
            color: #10B981;
            padding: 16px;
            border-radius: 8px;
            font-weight: 600;
            text-align: center;
            margin-top: 16px;
        }
    </style>
</head>
<body>
    <div class="card">
        <div class="logo">
            <div class="logo-icon">▶</div>
            <h1>Re:Player TV</h1>
        </div>
        <div class="status-badge">Сессия ТВ: $session</div>
        <p class="subtitle">Подключение Google Drive к телевизору. Работает на Яндекс ТВ, Android TV и приставках без сервисов Google Play.</p>

        <div class="info-box">
            <b>Как получить токен (1 минута):</b><br>
            1) На компьютере запустите <code>python tools/auth_gdrive.py</code> из репозитория Re:Player.<br>
            2) Скрипт откроет браузер, вы разрешите доступ к Диску и получите <b>Refresh Token</b>.<br>
            3) Скопируйте все три значения ниже и нажмите «Подключить к ТВ».<br><br>
            <b>ВАЖНО:</b> Client ID и Client Secret должны быть точно такими же, как вы вводили в <code>auth_gdrive.py</code>. Refresh Token привязан к этому Client ID.
        </div>

        <label for="clientId">Client ID (обязательно):</label>
        <input type="text" id="clientId" value="$initialClientId" placeholder="...apps.googleusercontent.com">

        <label for="clientSecret">Client Secret (обязательно):</label>
        <input type="text" id="clientSecret" value="$initialClientSecret" placeholder="GOCSPX-...">

        <label for="refreshToken">Refresh Token (обязательно, начинается с 1//):</label>
        <input type="text" id="refreshToken" placeholder="1//04...">

        <button type="button" class="btn" onclick="sendToTv()">🚀 Подключить к ТВ</button>

        <div id="successBox" class="success-box">
            ✅ Данные успешно переданы на телевизор!<br>Экран ТВ сейчас обновится.
        </div>
    </div>

    <script>
        function sendToTv() {
            var clientId = document.getElementById('clientId').value.trim();
            var clientSecret = document.getElementById('clientSecret').value.trim();
            var refreshToken = document.getElementById('refreshToken').value.trim();

            if (!clientId) {
                alert('Введите Client ID — он нужен, чтобы ТВ мог обновлять токен.');
                return;
            }
            if (!clientSecret) {
                alert('Введите Client Secret — он нужен, чтобы ТВ мог обновлять токен.');
                return;
            }
            if (!refreshToken) {
                alert('Введите Refresh Token (начинается с 1//).');
                return;
            }

            var payload = {
                client_id: clientId,
                client_secret: clientSecret,
                refresh_token: refreshToken,
                session: '$session'
            };
            var jsonPayload = JSON.stringify(payload);

            // 1. Напрямую на локальный сервер ТВ (если телефон в одной Wi-Fi сети)
            fetch('/api/token', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: jsonPayload
            }).then(function(res) {
                return res.json();
            }).then(function() {
                document.getElementById('successBox').style.display = 'block';
            }).catch(function() {
                // 2. Fallback через ntfy.sh (если телефон в LTE)
                fetch('https://ntfy.sh/replayer_pair_$session', {
                    method: 'POST',
                    body: jsonPayload
                }).then(function() {
                    document.getElementById('successBox').style.display = 'block';
                }).catch(function() {
                    alert('Не удалось передать токен. Проверьте, что телефон в одной Wi-Fi с ТВ.');
                });
            });
        }
    </script>
</body>
</html>
        """.trimIndent()
    }
}

