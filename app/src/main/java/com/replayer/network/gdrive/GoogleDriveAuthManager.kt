package com.replayer.network.gdrive

import android.content.Context
import com.replayer.model.DeviceCodeResponse
import com.replayer.model.TokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class GoogleDriveAuthManager(private val context: Context) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("replayer_drive_prefs", Context.MODE_PRIVATE)

    companion object {
        // Standard Google OAuth2 Device Endpoint
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }

    fun getSavedAccessToken(): String? = prefs.getString("access_token", null)

    fun getSavedClientId(): String = prefs.getString("gdrive_client_id", "") ?: ""

    fun saveClientId(clientId: String) {
        prefs.edit().putString("gdrive_client_id", clientId.trim()).apply()
    }

    fun saveTokens(accessToken: String, refreshToken: String?) {
        prefs.edit().apply {
            putString("access_token", accessToken)
            if (refreshToken != null) putString("refresh_token", refreshToken)
            apply()
        }
    }

    fun clearAuth() {
        prefs.edit().remove("access_token").remove("refresh_token").apply()
    }

    suspend fun requestDeviceCode(clientId: String): Result<DeviceCodeResponse> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || clientId.contains("YOUR_TV_CLIENT_ID")) {
            return@withContext Result.failure(
                Exception("Укажите ваш OAuth 2.0 Client ID (тип: TVs and Limited Input devices) из Google Cloud Console.")
            )
        }

        val form = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("scope", SCOPE)
            .build()

        val request = Request.Builder().url(DEVICE_CODE_URL).post(form).build()
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Пустой ответ от Google"))
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Ошибка Google API (${response.code}): $body"))
            }
            val codeResp = json.decodeFromString<DeviceCodeResponse>(body)
            saveClientId(clientId)
            Result.success(codeResp)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isAuthenticated(): Boolean = !getSavedAccessToken().isNullOrBlank()

    suspend fun pollForToken(
        clientId: String,
        deviceCode: String,
        clientSecret: String = "",
        intervalSec: Int = 5,
        maxRetries: Int = 60
    ): TokenResponse? = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        if (clientSecret.isNotBlank()) {
            formBuilder.add("client_secret", clientSecret.trim())
        }
        val form = formBuilder.build()

        var retries = 0
        while (retries < maxRetries) {
            delay(intervalSec * 1000L)
            retries++

            val request = Request.Builder().url(TOKEN_URL).post(form).build()
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: continue
                val tokenResp = json.decodeFromString<TokenResponse>(body)

                if (tokenResp.access_token != null) {
                    saveTokens(tokenResp.access_token, tokenResp.refresh_token)
                    return@withContext tokenResp
                }
                if (tokenResp.error != "authorization_pending") {
                    return@withContext tokenResp
                }
            } catch (e: Exception) {
                // Continue polling
            }
        }
        null
    }
}
