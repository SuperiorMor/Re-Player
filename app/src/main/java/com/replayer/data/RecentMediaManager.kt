package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import com.replayer.model.RecentMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RecentMediaManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("replayer_recent_media", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _recentList = MutableStateFlow<List<RecentMedia>>(loadRecent())
    val recentList: StateFlow<List<RecentMedia>> = _recentList.asStateFlow()

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

    fun addRecent(id: String, title: String, uri: String) {
        val current = _recentList.value.toMutableList()
        current.removeAll { it.id == id || it.uri == uri }
        current.add(0, RecentMedia(id = id, title = title, uri = uri, timestamp = System.currentTimeMillis()))
        // Keep last 15 items
        val trimmed = current.take(15)
        saveRecent(trimmed)
    }

    fun removeRecent(id: String) {
        val current = _recentList.value.filterNot { it.id == id }
        saveRecent(current)
    }
}
