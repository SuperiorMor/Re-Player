package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import com.replayer.model.EpgProgram
import com.replayer.model.IptvChannel
import com.replayer.network.iptv.M3uParser
import com.replayer.network.iptv.XmlTvParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class IptvManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("replayer_iptv", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _channels = MutableStateFlow<List<IptvChannel>>(loadCachedChannels())
    val channels: StateFlow<List<IptvChannel>> = _channels.asStateFlow()

    private val _favoriteChannelIds = MutableStateFlow<Set<String>>(loadFavoriteChannelIds())
    val favoriteChannelIds: StateFlow<Set<String>> = _favoriteChannelIds.asStateFlow()

    private val _epgMap = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val epgMap: StateFlow<Map<String, List<EpgProgram>>> = _epgMap.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        // Load EPG if cached or available
        val savedEpg = getSavedEpgUrl()
        if (!savedEpg.isNullOrBlank()) {
            scope.launch { fetchEpg(savedEpg) }
        }
    }

    fun getSavedPlaylistUrl(): String? = prefs.getString("iptv_playlist_url", null)

    fun getSavedEpgUrl(): String? = prefs.getString("iptv_epg_url", null)

    fun getGroups(): List<String> {
        val groups = _channels.value.map { it.group }.distinct().sorted()
        return listOf("Все", "Избранное") + groups
    }

    fun isFavorite(channelId: String): Boolean = _favoriteChannelIds.value.contains(channelId)

    fun toggleFavorite(channelId: String) {
        val current = _favoriteChannelIds.value.toMutableSet()
        if (current.contains(channelId)) {
            current.remove(channelId)
        } else {
            current.add(channelId)
        }
        _favoriteChannelIds.value = current
        prefs.edit().putStringSet("iptv_favorites", current).apply()
    }

    fun getFavoriteChannels(): List<IptvChannel> {
        val favIds = _favoriteChannelIds.value
        return _channels.value.filter { favIds.contains(it.id) }
    }

    fun getCurrentProgram(channel: IptvChannel): EpgProgram? {
        val map = _epgMap.value
        val list = (channel.tvgId?.let { map[it] }) ?: map[channel.name] ?: return null
        val now = System.currentTimeMillis()
        return list.firstOrNull { it.isCurrent(now) }
    }

    fun saveConfigAndLoad(playlistUrl: String, epgUrl: String?) {
        val cleanPlaylist = playlistUrl.trim()
        val cleanEpg = epgUrl?.trim()?.ifBlank { null }
        prefs.edit()
            .putString("iptv_playlist_url", cleanPlaylist)
            .putString("iptv_epg_url", cleanEpg)
            .apply()

        scope.launch {
            loadPlaylist(cleanPlaylist, cleanEpg)
        }
    }

    suspend fun refreshPlaylist() {
        val url = getSavedPlaylistUrl() ?: return
        val epg = getSavedEpgUrl()
        loadPlaylist(url, epg)
    }

    private suspend fun loadPlaylist(url: String, manualEpgUrl: String?) = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext
        _isLoading.value = true
        _statusMessage.value = "Загрузка плейлиста..."

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "RePlayer-TV")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                _statusMessage.value = "Ошибка загрузки: HTTP ${resp.code}"
                _isLoading.value = false
                return@withContext
            }

            val bodyStream = resp.body?.byteStream()
            if (bodyStream == null) {
                _statusMessage.value = "Пустой ответ сервера"
                _isLoading.value = false
                return@withContext
            }

            val parseResult = M3uParser.parse(bodyStream)
            _channels.value = parseResult.channels
            saveCachedChannels(parseResult.channels)

            val detectedOrManualEpg = manualEpgUrl ?: parseResult.epgUrl
            if (!detectedOrManualEpg.isNullOrBlank()) {
                prefs.edit().putString("iptv_epg_url", detectedOrManualEpg).apply()
                _statusMessage.value = "Каналов: ${parseResult.channels.size}. Загрузка программы передач..."
                fetchEpg(detectedOrManualEpg)
            } else {
                _statusMessage.value = "Каналов загружено: ${parseResult.channels.size}"
            }
        } catch (e: Exception) {
            _statusMessage.value = "Ошибка: ${e.localizedMessage ?: "Сбой соединения"}"
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun fetchEpg(epgUrl: String) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(epgUrl)
                .header("User-Agent", "RePlayer-TV")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext

            val stream = resp.body?.byteStream() ?: return@withContext
            val isGzip = epgUrl.endsWith(".gz", ignoreCase = true)
            val parsed = XmlTvParser.parse(stream, isGzip = isGzip)
            _epgMap.value = parsed
            _statusMessage.value = "Каналов: ${_channels.value.size}. Программа передач обновлена."
        } catch (_: Exception) {}
    }

    private fun loadFavoriteChannelIds(): Set<String> {
        return prefs.getStringSet("iptv_favorites", emptySet()) ?: emptySet()
    }

    private fun loadCachedChannels(): List<IptvChannel> {
        return try {
            val file = File(context.cacheDir, "iptv_channels.json")
            if (file.exists()) {
                json.decodeFromString<List<IptvChannel>>(file.readText())
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCachedChannels(channels: List<IptvChannel>) {
        try {
            val file = File(context.cacheDir, "iptv_channels.json")
            file.writeText(json.encodeToString(channels))
        } catch (_: Exception) {}
    }
}
