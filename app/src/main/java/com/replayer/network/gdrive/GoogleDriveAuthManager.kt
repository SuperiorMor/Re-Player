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
        // Clear any stale custom secret/client id from legacy versions to ensure the exact matching pair is used
        val legacySecret = prefs.getString("gdrive_client_secret", null)
        if (legacySecret != null && (legacySecret.length != 35 || !legacySecret.contains("xjh"))) {
            prefs.edit().remove("gdrive_client_secret").apply()
        }
        val legacyId = prefs.getString("gdrive_client_id", null)
        if (legacyId != null && !legacyId.startsWith("673747062826-sdv9vfgbi")) {
            prefs.edit().remove("gdrive_client_id").apply()
        }
        _isAuthenticatedFlow.value = isAuthenticated()
    }

    val isAuthenticatedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAuthenticatedFlow

    companion object {
        private val _isAuthenticatedFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val sharedAuthenticatedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAuthenticatedFlow

        // Standard Google OAuth2 Device Endpoint
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val PRIMARY_SCOPE = "https://www.googleapis.com/auth/drive.readonly email profile"
        private const val FALLBACK_SCOPE = "https://www.googleapis.com/auth/drive.file email profile"

        // Built-in pre-configured Client ID & Secret (Desktop application client)
        private val OBF_ID = byteArrayOf(
            108, 109, 105, 109, 110, 109, 106, 108, 104, 98, 104, 108, 119, 41, 62, 44, 99, 44, 60, 61, 56, 51, 110, 108, 108, 109, 44, 55, 49, 40, 62, 99, 106, 47, 50, 60, 105, 98, 107, 41, 109, 111, 40, 57, 105, 116, 59, 42, 42, 41, 116, 61, 53, 53, 61, 54, 63, 47, 41, 63, 40, 57, 53, 52, 46, 63, 52, 46, 116, 57, 53, 55
        )
        // 35-character full client secret: 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
        private val OBF_SEC = byteArrayOf(
            29, 21, 25, 9, 10, 2, 119, 51, 54, 31, 32, 60, 32, 105, 28, 56, 13, 30, 40, 46, 23, 31, 12, 30, 63, 21, 2, 18, 34, 48, 50, 14, 50, 30, 105
        )

        val DEFAULT_CLIENT_ID: String get() = try {
            OBF_ID.map { (it.toInt() xor 0x5A).toChar() }.joinToString("")
        } catch (_: Exception) { "" }

        val DEFAULT_CLIENT_SECRET: String get() = try {
            OBF_SEC.map { (it.toInt() xor 0x5A).toChar() }.joinToString("")
        } catch (_: Exception) { "" }
    }

    fun getSavedAccessToken(): String? = prefs.getString("access_token", null)

    fun getSavedClientId(): String {
        val custom = prefs.getString("gdrive_client_id", "") ?: ""
        return if (custom.isNotBlank() && custom.startsWith("673747062826-sdv9vfgbi")) custom else DEFAULT_CLIENT_ID
    }

    fun saveClientId(clientId: String) {
        prefs.edit().putString("gdrive_client_id", clientId.trim()).apply()
    }

    fun getSavedClientSecret(): String {
        val custom = prefs.getString("gdrive_client_secret", "") ?: ""
        return if (custom.isNotBlank() && custom.length == 35 && custom.contains("xjh")) custom else DEFAULT_CLIENT_SECRET
    }

    fun saveClientSecret(secret: String) {
        prefs.edit().putString("gdrive_client_secret", secret.trim()).apply()
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

    suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString("refresh_token", null) ?: return@withContext null
        val clientId = getSavedClientId()
        val clientSecret = getSavedClientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) return@withContext null

        val form = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("client_secret", clientSecret.trim())
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) {
                android.util.Log.e("DriveAuth", "Refresh failed (${response.code}): $body")
                return@withContext null
            }
            val tokenResp = json.decodeFromString<TokenResponse>(body)
            if (!tokenResp.access_token.isNullOrBlank()) {
                saveTokens(tokenResp.access_token, tokenResp.refresh_token ?: refreshToken)
                return@withContext tokenResp.access_token
            }
        } catch (e: Exception) {
            android.util.Log.e("DriveAuth", "Refresh exception: ${e.message}")
        }
        null
    }

    suspend fun requestDeviceCode(clientId: String = getSavedClientId()): Result<DeviceCodeResponse> = withContext(Dispatchers.IO) {
        val actualClientId = clientId.ifBlank { getSavedClientId() }
        if (actualClientId.isBlank() || actualClientId.contains("YOUR_TV_CLIENT_ID")) {
            return@withContext Result.failure(
                Exception("Client ID не настроен.")
            )
        }

        val formPrimary = FormBody.Builder()
            .add("client_id", actualClientId.trim())
            .add("scope", PRIMARY_SCOPE)
            .build()

        val requestPrimary = Request.Builder().url(DEVICE_CODE_URL).post(formPrimary).build()
        try {
            val responsePrimary = client.newCall(requestPrimary).execute()
            val bodyPrimary = responsePrimary.body?.string() ?: ""
            if (responsePrimary.isSuccessful) {
                val codeResp = json.decodeFromString<DeviceCodeResponse>(bodyPrimary)
                saveClientId(actualClientId)
                return@withContext Result.success(codeResp)
            }

            // If invalid_scope, gracefully retry with fallback scope
            if (bodyPrimary.contains("invalid_scope")) {
                val formFallback = FormBody.Builder()
                    .add("client_id", actualClientId.trim())
                    .add("scope", FALLBACK_SCOPE)
                    .build()
                val requestFallback = Request.Builder().url(DEVICE_CODE_URL).post(formFallback).build()
                val responseFallback = client.newCall(requestFallback).execute()
                val bodyFallback = responseFallback.body?.string() ?: ""
                if (responseFallback.isSuccessful) {
                    val codeResp = json.decodeFromString<DeviceCodeResponse>(bodyFallback)
                    saveClientId(actualClientId)
                    return@withContext Result.success(codeResp)
                }
                return@withContext Result.failure(Exception("Ошибка Google API (${responseFallback.code}): $bodyFallback"))
            }

            return@withContext Result.failure(Exception("Ошибка Google API (${responsePrimary.code}): $bodyPrimary"))
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
        val effectiveSecret = clientSecret.ifBlank { getSavedClientSecret() }
        val formBuilder = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        if (effectiveSecret.isNotBlank()) {
            formBuilder.add("client_secret", effectiveSecret.trim())
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
