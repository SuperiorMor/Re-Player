package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import com.replayer.model.RecentMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    private val _recentList = MutableStateFlow<List<RecentMedia>>(loadRecent())
    val recentList: StateFlow<List<RecentMedia>> = _recentList.asStateFlow()

    private val _progressMap = MutableStateFlow<Map<String, MediaWatchProgress>>(loadProgressMap())
    val progressMap: StateFlow<Map<String, MediaWatchProgress>> = _progressMap.asStateFlow()

    private fun loadRecent(): List<RecentMedia> {
        val raw = prefs.getString("recent_media_json", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<RecentMedia>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRecent(list: List<RecentMedia>) {
        _recentList.value = list
        try {
            val encoded = json.encodeToString(list)
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

    fun addRecent(id: String, title: String, uri: String) {
        val current = _recentList.value.toMutableList()
        val existing = current.find { it.id == id || it.uri == uri }
        val savedProg = _progressMap.value[id] ?: _progressMap.value[uri]
        val pos = existing?.positionMs?.takeIf { it > 0L } ?: savedProg?.positionMs ?: 0L
        val dur = existing?.durationMs?.takeIf { it > 0L } ?: savedProg?.durationMs ?: 0L

        current.removeAll { it.id == id || it.uri == uri }
        current.add(
            0,
            RecentMedia(
                id = id,
                title = title,
                uri = uri,
                timestamp = System.currentTimeMillis(),
                positionMs = pos,
                durationMs = dur
            )
        )
        saveRecent(current.take(15))
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
        // Resume if > 2% and < 95% (don't resume if already at very end/credits)
        return if (frac in 0.01f..0.95f) entry.positionMs else 0L
    }

    fun removeRecent(id: String) {
        val current = _recentList.value.filterNot { it.id == id }
        saveRecent(current)
    }
}
