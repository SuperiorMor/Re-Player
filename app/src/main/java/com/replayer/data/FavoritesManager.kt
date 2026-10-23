package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import com.replayer.model.FavoriteFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FavoritesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("replayer_favorites", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _favorites = MutableStateFlow<List<FavoriteFolder>>(loadFavorites())
    val favorites: StateFlow<List<FavoriteFolder>> = _favorites.asStateFlow()

    private fun loadFavorites(): List<FavoriteFolder> {
        val raw = prefs.getString("favorites_list", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<FavoriteFolder>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveFavorites(list: List<FavoriteFolder>) {
        _favorites.value = list
        try {
            val encoded = json.encodeToString(list)
            prefs.edit().putString("favorites_list", encoded).apply()
        } catch (_: Exception) {}
    }

    fun isFavorite(id: String): Boolean {
        return _favorites.value.any { it.id == id }
    }

    fun toggleFavorite(folder: FavoriteFolder) {
        val current = _favorites.value.toMutableList()
        val index = current.indexOfFirst { it.id == folder.id }
        if (index >= 0) {
            current.removeAt(index)
        } else {
            current.add(folder)
        }
        saveFavorites(current)
    }

    fun removeFavorite(id: String) {
        val current = _favorites.value.filterNot { it.id == id }
        saveFavorites(current)
    }
}
