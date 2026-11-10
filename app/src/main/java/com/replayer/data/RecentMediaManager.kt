package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import com.replayer.model.MediaItem
import com.replayer.model.MediaType
import com.replayer.model.RecentMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class MediaWatchProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun progress(): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

class RecentMediaManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: RecentMediaManager? = null

        fun getInstance(context: Context): RecentMediaManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RecentMediaManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("replayer_recent_media", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _recentList = MutableStateFlow<List<RecentMedia>>(loadRecent())
    val recentList: StateFlow<List<RecentMedia>> = _recentList.asStateFlow()

    private val _progressMap = MutableStateFlow<Map<String, MediaWatchProgress>>(loadProgressMap())
    val progressMap: StateFlow<Map<String, MediaWatchProgress>> = _progressMap.asStateFlow()

    private fun loadRecent(): List<RecentMedia> {
        val raw = prefs.getString("recent_media_json", null) ?: return emptyList()
        return try {
            val loaded = json.decodeFromString<List<RecentMedia>>(raw)
            // Enforce one item per folder & max 2 items
            val seenFolders = mutableSetOf<String>()
            loaded.filter { item ->
                val folderKey = item.parentId?.ifBlank { null } ?: item.id
                seenFolders.add(folderKey)
            }.take(2)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRecent(list: List<RecentMedia>) {
        val trimmed = list.take(2)
        _recentList.value = trimmed
        try {
            val encoded = json.encodeToString(trimmed)
            prefs.edit().putString("recent_media_json", encoded).apply()
        } catch (_: Exception) {}
    }

    private fun loadProgressMap(): Map<String, MediaWatchProgress> {
        val raw = prefs.getString("watch_progress_json", null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, MediaWatchProgress>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveProgressMap(map: Map<String, MediaWatchProgress>) {
        val trimmed = if (map.size > 300) {
            map.entries
                .sortedByDescending { it.value.updatedAt }
                .take(300)
                .associate { it.key to it.value }
        } else map

        _progressMap.value = trimmed
        try {
            prefs.edit().putString("watch_progress_json", json.encodeToString(trimmed)).apply()
        } catch (_: Exception) {}
    }

    fun addRecent(
        id: String,
        title: String,
        uri: String,
        parentId: String? = null,
        folderTitle: String? = null,
        thumbnailUrl: String? = null
    ) {
        val current = _recentList.value.toMutableList()
        val sameExact = current.find { it.id == id || it.uri == uri }
        val sameFolder = if (!parentId.isNullOrBlank()) current.find { it.parentId == parentId } else null

        val savedProg = _progressMap.value[id] ?: _progressMap.value[uri]
        val pos = sameExact?.positionMs?.takeIf { it > 0L } ?: savedProg?.positionMs ?: 0L
        val dur = sameExact?.durationMs?.takeIf { it > 0L } ?: savedProg?.durationMs ?: 0L

        val resolvedThumb = thumbnailUrl?.ifBlank { null }
            ?: sameExact?.thumbnailUrl?.ifBlank { null }
            ?: sameFolder?.thumbnailUrl?.ifBlank { null }

        // Deduplicate: keep only ONE entry per folder (or per item if parentId unknown)
        current.removeAll { item ->
            item.id == id || item.uri == uri || (!parentId.isNullOrBlank() && item.parentId == parentId)
        }
        current.add(
            0,
            RecentMedia(
                id = id,
                title = title,
                uri = uri,
                timestamp = System.currentTimeMillis(),
                positionMs = pos,
                durationMs = dur,
                thumbnailUrl = resolvedThumb,
                parentId = parentId?.ifBlank { null },
                folderTitle = folderTitle ?: sameFolder?.folderTitle
            )
        )
        // Limit section strictly to 2 folders
        saveRecent(current.take(2))
    }

    fun updateThumbnail(id: String, uri: String, thumbnailUrl: String) {
        val current = _recentList.value.toMutableList()
        var changed = false
        for (i in current.indices) {
            val item = current[i]
            if (item.id == id || item.uri == uri) {
                current[i] = item.copy(thumbnailUrl = thumbnailUrl)
                changed = true
            }
        }
        if (changed) {
            saveRecent(current)
        }
    }

    /**
     * Searches files in the same DLNA folder for a vertical (portrait, height > width) image
     * and attaches it as cover art for the recent item.
     */
    fun resolveVerticalPoster(targetItem: MediaItem, folderItems: List<MediaItem>) {
        scope.launch {
            val videoStem = targetItem.title.substringBeforeLast('.').lowercase().trim()
            val images = folderItems
                .filter { it.type == MediaType.IMAGE && it.uri.isNotBlank() }
                .sortedBy { img ->
                    val imgStem = img.title.substringBeforeLast('.').lowercase().trim()
                    when {
                        imgStem == videoStem -> 0
                        imgStem.contains("poster") || imgStem.contains("cover") || imgStem == "folder" -> 1
                        else -> 2
                    }
                }

            for (img in images) {
                if (isVerticalImage(img.uri, img.resolution)) {
                    updateThumbnail(targetItem.id, targetItem.uri, img.uri)
                    return@launch
                }
            }

            // Fallback: check server-provided albumArtURI on the video item itself if it is vertical
            val art = targetItem.thumbnailUrl
            if (!art.isNullOrBlank() && isVerticalImage(art, null)) {
                updateThumbnail(targetItem.id, targetItem.uri, art)
            }
        }
    }

    private fun isVerticalImage(url: String, resolutionHint: String?): Boolean {
        if (!resolutionHint.isNullOrBlank()) {
            val parts = resolutionHint.lowercase().split('x')
            if (parts.size == 2) {
                val w = parts[0].trim().toIntOrNull()
                val h = parts[1].trim().toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) {
                    return h > w
                }
            }
        }

        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val stream = resp.body?.byteStream() ?: return false
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, opts)
                opts.outWidth > 0 && opts.outHeight > opts.outWidth
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun updateProgress(id: String, title: String, uri: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L || positionMs < 0L) return

        val entry = MediaWatchProgress(
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis()
        )
        val updatedMap = _progressMap.value.toMutableMap()
        updatedMap[id] = entry
        if (uri.isNotBlank()) updatedMap[uri] = entry
        saveProgressMap(updatedMap)

        val currentRecent = _recentList.value.toMutableList()
        val idx = currentRecent.indexOfFirst { it.id == id || it.uri == uri }
        if (idx >= 0) {
            currentRecent[idx] = currentRecent[idx].copy(
                positionMs = positionMs,
                durationMs = durationMs,
                timestamp = System.currentTimeMillis()
            )
            saveRecent(currentRecent)
        }
    }

    fun getSavedResumePosition(id: String, uri: String? = null): Long {
        val entry = _progressMap.value[id] ?: uri?.let { _progressMap.value[it] } ?: return 0L
        if (entry.durationMs <= 10_000L || entry.positionMs < 5_000L) return 0L
        val frac = entry.progress()
        return if (frac in 0.01f..0.95f) entry.positionMs else 0L
    }

    fun removeRecent(id: String) {
        val current = _recentList.value.filterNot { it.id == id }
        saveRecent(current)
    }
}
