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

    init {
        _isAuthenticatedFlow.value = isAuthenticated()
    }

    val isAuthenticatedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAuthenticatedFlow

    companion object {
        private val _isAuthenticatedFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val sharedAuthenticatedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAuthenticatedFlow

        // Standard Google OAuth2 Device Endpoint
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        // Valid Google OAuth2 Device Endpoint Scope
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file email profile"

        // Built-in pre-configured Client ID
        const val DEFAULT_CLIENT_ID = "673747062826-9rlftnhrpuqspo97jahao1n2172efmnh.apps.googleusercontent.com"
    }

    fun getSavedAccessToken(): String? = prefs.getString("access_token", null)

    fun getSavedClientId(): String {
        val custom = prefs.getString("gdrive_client_id", "") ?: ""
        return if (custom.isNotBlank()) custom else DEFAULT_CLIENT_ID
    }

    fun saveClientId(clientId: String) {
        prefs.edit().putString("gdrive_client_id", clientId.trim()).apply()
    }

    fun saveTokens(accessToken: String, refreshToken: String?) {
        prefs.edit().apply {
            putString("access_token", accessToken)
            if (refreshToken != null) putString("refresh_token", refreshToken)
            apply()
        }
        _isAuthenticatedFlow.value = true
    }

    fun clearAuth() {
        prefs.edit().remove("access_token").remove("refresh_token").apply()
        _isAuthenticatedFlow.value = false
    }

    suspend fun requestDeviceCode(clientId: String = getSavedClientId()): Result<DeviceCodeResponse> = withContext(Dispatchers.IO) {
        val actualClientId = clientId.ifBlank { getSavedClientId() }
        if (actualClientId.isBlank() || actualClientId.contains("YOUR_TV_CLIENT_ID")) {
            return@withContext Result.failure(
                Exception("Client ID не настроен.")
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
        maxRetries: Int = 120
    ): TokenResponse? = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        if (clientSecret.isNotBlank()) {
            formBuilder.add("client_secret", clientSecret.trim())
        }
        val form = formBuilder.build()

        var currentInterval = intervalSec.coerceAtLeast(5)
        var retries = 0
        while (retries < maxRetries) {
            delay(currentInterval * 1000L)
            retries++

            val request = Request.Builder().url(TOKEN_URL).post(form).build()
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: continue
                android.util.Log.d("DriveAuth", "Poll ($retries): code=${response.code}, body=$body")

                val tokenResp = json.decodeFromString<TokenResponse>(body)

                if (!tokenResp.access_token.isNullOrBlank()) {
                    saveTokens(tokenResp.access_token, tokenResp.refresh_token)
                    return@withContext tokenResp
                }

                when (tokenResp.error) {
                    "authorization_pending" -> {
                        // User is still authorizing on phone, keep polling
                    }
                    "slow_down" -> {
                        // Rate limit: increase polling interval by 5 seconds
                        currentInterval += 5
                    }
                    "access_denied", "expired_token" -> {
                        return@withContext tokenResp
                    }
                    else -> {
                        if (tokenResp.error != null) {
                            return@withContext tokenResp
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DriveAuth", "Poll exception: ${e.message}")
            }
        }
        null
    }
}
