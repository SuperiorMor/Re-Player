package com.replayer.updater

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.replayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class UpdateChannel(val prefValue: String, val label: String) {
    STABLE("stable", "Stable"),
    BETA("beta", "Beta")
}

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val fileName: String,
    val fileSize: Long,
    val isPrerelease: Boolean = false
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    object NoReleasesFound : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class UpdateManager(private val context: Context) {
    private val prefs by lazy {
        context.getSharedPreferences("replayer_prefs", Context.MODE_PRIVATE)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val repoOwner = "SuperiorMor"
    private val repoName = "Re-Player"

    fun getUpdateChannel(): UpdateChannel {
        val saved = prefs.getString(KEY_UPDATE_CHANNEL, null)
        return when (saved?.lowercase()) {
            UpdateChannel.BETA.prefValue -> UpdateChannel.BETA
            UpdateChannel.STABLE.prefValue -> UpdateChannel.STABLE
            else -> if (BuildConfig.VERSION_NAME.contains("beta", ignoreCase = true)) {
                UpdateChannel.BETA
            } else {
                UpdateChannel.STABLE
            }
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        prefs.edit().putString(KEY_UPDATE_CHANNEL, channel.prefValue).apply()
    }

    fun cleanOldUpdates() {
        try {
            val dir = context.externalCacheDir ?: context.cacheDir
            val updatesDir = File(dir, "updates")
            if (updatesDir.exists() && updatesDir.isDirectory) {
                updatesDir.listFiles()?.forEach { file ->
                    if (file.isFile) file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w("UpdateManager", "Failed to clean old updates", e)
        }
    }

    suspend fun checkForUpdates(
        currentVersion: String,
        channel: UpdateChannel = getUpdateChannel()
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        cleanOldUpdates()
        try {
            val url = if (channel == UpdateChannel.BETA) {
                "https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=15"
            } else {
                "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "RePlayer-App/v$currentVersion (Android TV)")
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 404) {
                return@withContext UpdateCheckResult.NoReleasesFound
            }
            if (response.code == 403 || !response.isSuccessful) {
                Log.w("UpdateManager", "GitHub API returned ${response.code}, falling back to web release check")
                return@withContext fetchLatestReleaseViaHtmlFallback(currentVersion, channel)
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                return@withContext fetchLatestReleaseViaHtmlFallback(currentVersion, channel)
            }

            val releaseInfo = if (channel == UpdateChannel.BETA) {
                pickLatestReleaseFromArray(JSONArray(body))
            } else {
                parseReleaseObject(JSONObject(body))
            } ?: return@withContext UpdateCheckResult.NoReleasesFound

            if (isNewerVersion(releaseInfo.versionName, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(releaseInfo)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Check update via API failed, trying web fallback", e)
            fetchLatestReleaseViaHtmlFallback(currentVersion, channel)
        }
    }

    private fun pickLatestReleaseFromArray(array: JSONArray): ReleaseInfo? {
        var best: ReleaseInfo? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val info = parseReleaseObject(obj) ?: continue
            if (best == null || compareVersions(info.versionName, best.versionName) > 0) {
                best = info
            }
        }
        return best
    }

    private fun parseReleaseObject(json: JSONObject): ReleaseInfo? {
        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isEmpty()) return null
        val cleanVersion = tagName.removePrefix("v").trim()
        val name = json.optString("name", "Re:Player $tagName")
        val changelog = json.optString("body", "Список изменений отсутствует")
        val isPrerelease = json.optBoolean("prerelease", false) ||
            cleanVersion.contains("beta", ignoreCase = true)

        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var apkName = "RePlayer-$tagName.apk"
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
            apkUrl = "https://github.com/$repoOwner/$repoName/releases/download/$tagName/$apkName"
        }

        return ReleaseInfo(
            tagName = tagName,
            versionName = cleanVersion,
            title = name,
            changelog = changelog,
            downloadUrl = apkUrl,
            fileName = apkName,
            fileSize = apkSize,
            isPrerelease = isPrerelease
        )
    }

    private fun fetchLatestReleaseViaHtmlFallback(
        currentVersion: String,
        channel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateCheckResult {
        return try {
            var targetTag: String? = null
            if (channel == UpdateChannel.BETA) {
                val html = client.newCall(
                    Request.Builder()
                        .url("https://github.com/$repoOwner/$repoName/releases")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RePlayer-TV-App/$currentVersion")
                        .build()
                ).execute().body?.string().orEmpty()
                val matches = Regex("""/$repoOwner/$repoName/releases/tag/(v[^"'?\s<]+)""")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
                targetTag = matches.maxWithOrNull { a, b ->
                    compareVersions(a.removePrefix("v"), b.removePrefix("v"))
                }
            } else {
                val redirectClient = client.newBuilder()
                    .followRedirects(false)
                    .build()

                val webUrl = "https://github.com/$repoOwner/$repoName/releases/latest"
                val webRequest = Request.Builder()
                    .url(webUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RePlayer-TV-App/$currentVersion")
                    .head()
                    .build()

                val webResponse = redirectClient.newCall(webRequest).execute()
                val location = webResponse.header("Location")
                if (!location.isNullOrBlank() && location.contains("/releases/tag/")) {
                    targetTag = location.substringAfterLast("/")
                }

                if (targetTag.isNullOrBlank()) {
                    val fullResponse = client.newCall(
                        Request.Builder()
                            .url(webUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RePlayer-TV-App/$currentVersion")
                            .build()
                    ).execute()
                    val finalUrl = fullResponse.request.url.toString()
                    if (finalUrl.contains("/releases/tag/")) {
                        targetTag = finalUrl.substringAfterLast("/")
                    }
                }
            }

            if (targetTag.isNullOrBlank()) {
                return UpdateCheckResult.Error("Не удалось определить последнюю версию с GitHub")
            }

            val cleanVersion = targetTag.removePrefix("v").trim()
            val apkName = "RePlayer-v$cleanVersion.apk"
            val apkUrl = "https://github.com/$repoOwner/$repoName/releases/download/$targetTag/$apkName"

            val releaseInfo = ReleaseInfo(
                tagName = targetTag,
                versionName = cleanVersion,
                title = "Re:Player $targetTag",
                changelog = "Обновление доступно для установки",
                downloadUrl = apkUrl,
                fileName = apkName,
                fileSize = 0L,
                isPrerelease = cleanVersion.contains("beta", ignoreCase = true)
            )

            if (isNewerVersion(cleanVersion, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(releaseInfo)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Fallback release check failed", e)
            UpdateCheckResult.Error(e.localizedMessage ?: "Ошибка сети при проверке обновлений")
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        fileName: String = "RePlayer-update.apk",
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = context.externalCacheDir ?: context.cacheDir
            val updatesDir = File(dir, "updates").apply { mkdirs() }
            // Remove any old downloaded update files to free up disk space immediately
            updatesDir.listFiles()?.forEach { file ->
                try { if (file.isFile) file.delete() } catch (_: Exception) {}
            }
            val targetFile = File(updatesDir, fileName)
            if (targetFile.exists()) targetFile.delete()

            val downloadClient = client.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RePlayer-TV-App")
                .build()
            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(32768)
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

    fun isNewerVersion(remote: String, current: String): Boolean =
        compareVersions(remote, current) > 0

    fun compareVersions(remote: String, current: String): Int {
        val r = parseSemVer(remote)
        val c = parseSemVer(current)

        val maxLen = maxOf(r.parts.size, c.parts.size)
        for (i in 0 until maxLen) {
            val rv = r.parts.getOrElse(i) { 0 }
            val cv = c.parts.getOrElse(i) { 0 }
            if (rv != cv) return rv.compareTo(cv)
        }

        // Stable (isBeta = false) is strictly newer than any beta of the same base version
        if (!r.isBeta && c.isBeta) return 1
        if (r.isBeta && !c.isBeta) return -1
        if (!r.isBeta && !c.isBeta) return 0
        return r.betaNum.compareTo(c.betaNum)
    }

    private data class ParsedSemVer(
        val parts: List<Int>,
        val isBeta: Boolean,
        val betaNum: Int
    )

    private fun parseSemVer(version: String): ParsedSemVer {
        val clean = version.removePrefix("v").trim()
        val match = SEMVER_REGEX.matchEntire(clean)
        if (match != null) {
            val parts = match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
            val hasBeta = match.groupValues[2].isNotEmpty()
            val betaNum = match.groupValues[3].toIntOrNull() ?: 0
            return ParsedSemVer(parts = parts, isBeta = hasBeta, betaNum = betaNum)
        }
        val basePart = clean.substringBefore('-')
        val parts = basePart.split('.').mapNotNull { it.toIntOrNull() }
        val isBeta = clean.contains("beta", ignoreCase = true)
        val betaNum = Regex("""\d+$""").find(clean.substringAfter('-', ""))?.value?.toIntOrNull() ?: 0
        return ParsedSemVer(parts = parts, isBeta = isBeta, betaNum = betaNum)
    }

    companion object {
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private val SEMVER_REGEX = Regex("""^(\d+(?:\.\d+)*)(-(?:beta|rc|alpha)(?:\.?(\d+))?)?$""", RegexOption.IGNORE_CASE)
    }
}
