package com.replayer.updater

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val fileName: String,
    val fileSize: Long
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    object NoReleasesFound : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class UpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val repoOwner = "SuperiorMor"
    private val repoName = "Re-Player"

    suspend fun checkForUpdates(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 404) {
                return@withContext UpdateCheckResult.NoReleasesFound
            }
            if (!response.isSuccessful) {
                return@withContext UpdateCheckResult.Error("Ошибка проверки обновления: HTTP ${response.code}")
            }

            val body = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Пустой ответ от сервера")
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val cleanVersion = tagName.removePrefix("v").trim()
            val name = json.optString("name", "Re:Player $tagName")
            val changelog = json.optString("body", "Список изменений отсутствует")

            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            var apkName = "RePlayer-update.apk"
            var apkSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkName = assetName
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isNullOrEmpty()) {
                return@withContext UpdateCheckResult.Error("В релизе $tagName не найден файл APK")
            }

            val releaseInfo = ReleaseInfo(
                tagName = tagName,
                versionName = cleanVersion,
                title = name,
                changelog = changelog,
                downloadUrl = apkUrl,
                fileName = apkName,
                fileSize = apkSize
            )

            if (isNewerVersion(cleanVersion, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(releaseInfo)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Check update failed", e)
            UpdateCheckResult.Error(e.localizedMessage ?: "Ошибка сети при проверке обновлений")
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updatesDir, "replayer-update.apk")
            if (targetFile.exists()) targetFile.delete()

            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            onProgress((totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }

            onProgress(1.0f)
            targetFile
        } catch (e: Exception) {
            Log.e("UpdateManager", "Download failed", e)
            null
        }
    }

    fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Install intent failed", e)
        }
    }

    fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
