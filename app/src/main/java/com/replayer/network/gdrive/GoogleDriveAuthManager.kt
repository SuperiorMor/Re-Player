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

    fun saveTokens(accessToken: String, refreshToken: String?) {
        prefs.edit().apply {
            putString("access_token", accessToken)
            if (refreshToken != null) putString("refresh_token", refreshToken)
            apply()
        }
    }

    fun clearAuth() {
        prefs.edit().clear().apply()
    }

    suspend fun requestDeviceCode(clientId: String): DeviceCodeResponse? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build()

        val request = Request.Builder().url(DEVICE_CODE_URL).post(form).build()
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            json.decodeFromString<DeviceCodeResponse>(body)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun pollForToken(
        clientId: String,
        clientSecret: String,
        deviceCode: String,
        intervalSec: Int = 5,
        maxRetries: Int = 30
    ): TokenResponse? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()

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
