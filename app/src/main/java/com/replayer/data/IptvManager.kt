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
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

enum class ChannelSortMode(val label: String) {
    DEFAULT("По умолчанию"),
    BY_NAME("По названию (А-Я)"),
    BY_WATCH_TIME("По времени просмотра")
}

class IptvManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("replayer_iptv", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            var req = chain.request()
            val host = req.url.host
            // epg.it999.ru and epg.one redirect to a dead origin server; the working Cloudflare CDN endpoint is cdn.epg.one
            if (host.equals("epg.it999.ru", ignoreCase = true) || host.equals("epg.one", ignoreCase = true)) {
                val fixedUrl = req.url.newBuilder().host("cdn.epg.one").build()
                req = req.newBuilder().url(fixedUrl).build()
            }
            val resp = chain.proceed(req)
            if (resp.isRedirect) {
                val loc = resp.header("Location")
                if (!loc.isNullOrBlank() && (loc.contains("epg.it999.ru", ignoreCase = true) || loc.contains("://epg.one", ignoreCase = true))) {
                    val fixedLoc = loc
                        .replace("epg.it999.ru", "cdn.epg.one", ignoreCase = true)
                        .replace("://epg.one", "://cdn.epg.one", ignoreCase = true)
                    resp.close()
                    return@addInterceptor chain.proceed(req.newBuilder().url(fixedLoc).build())
                }
            }
            resp
        }
        .build()

    fun normalizeEpgUrl(url: String): String {
        var u = url.trim()
        if (u.contains("epg.it999.ru", ignoreCase = true) || u.contains("://epg.one", ignoreCase = true)) {
            u = u.replace("epg.it999.ru", "cdn.epg.one", ignoreCase = true)
                 .replace("://epg.one", "://cdn.epg.one", ignoreCase = true)
        }
        return u
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _channels = MutableStateFlow<List<IptvChannel>>(loadCachedChannels())
    val channels: StateFlow<List<IptvChannel>> = _channels.asStateFlow()

    private val _favoriteChannelIds = MutableStateFlow<Set<String>>(loadFavoriteChannelIds())
    val favoriteChannelIds: StateFlow<Set<String>> = _favoriteChannelIds.asStateFlow()

    private val _epgMap = MutableStateFlow<Map<String, List<EpgProgram>>>(loadCachedEpg())
    val epgMap: StateFlow<Map<String, List<EpgProgram>>> = _epgMap.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _playlistProgress = MutableStateFlow<Float?>(null)
    val playlistProgress: StateFlow<Float?> = _playlistProgress.asStateFlow()

    private val _epgProgress = MutableStateFlow<Float?>(null)
    val epgProgress: StateFlow<Float?> = _epgProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Watch duration in seconds: channelId -> seconds
    private val _watchTimes = MutableStateFlow<Map<String, Long>>(loadWatchTimes())
    val watchTimes: StateFlow<Map<String, Long>> = _watchTimes.asStateFlow()

    init {
        // If we have saved URLs, refresh EPG if cache is empty
        val savedEpg = getSavedEpgUrl()
        if (!savedEpg.isNullOrBlank() && _epgMap.value.isEmpty()) {
            scope.launch { fetchEpg(savedEpg) }
        }
    }

    fun getSavedPlaylistUrl(): String? = prefs.getString("iptv_playlist_url", null)

    fun getSavedEpgUrl(): String? = prefs.getString("iptv_epg_url", null)

    fun saveUrlsOnly(playlistUrl: String, epgUrl: String?) {
        prefs.edit()
            .putString("iptv_playlist_url", playlistUrl.trim())
            .putString("iptv_epg_url", epgUrl?.trim()?.ifBlank { null })
            .apply()
    }

    /**
     * Category folders (Requirement 5 & 8):
     * Excludes artificial "Все" and "Избранное" (favorites are on Home screen).
     * Replaces blank or "Общие" with "Другие".
     */
    fun getCategoryFolders(): List<String> {
        val groups = _channels.value
            .map { it.group }
            .distinct()
            .filter { it != "Все" && it != "Избранное" }
            .sorted()
        return groups.ifEmpty { listOf("Все каналы") }
    }

    fun getChannelsInFolder(folder: String): List<IptvChannel> {
        return if (folder == "Все каналы") {
            _channels.value
        } else {
            _channels.value.filter { it.group == folder }
        }
    }

    fun getSortedChannels(channels: List<IptvChannel>, sortMode: ChannelSortMode): List<IptvChannel> {
        return when (sortMode) {
            ChannelSortMode.DEFAULT -> channels
            ChannelSortMode.BY_NAME -> channels.sortedBy { it.name.lowercase() }
            ChannelSortMode.BY_WATCH_TIME -> {
                val times = _watchTimes.value
                channels.sortedByDescending { times[it.id] ?: 0L }
            }
        }
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

    fun recordWatchTime(channelId: String, deltaSeconds: Long) {
        if (deltaSeconds <= 0) return
        val current = _watchTimes.value.toMutableMap()
        val old = current[channelId] ?: 0L
        current[channelId] = old + deltaSeconds
        _watchTimes.value = current
        saveWatchTimes(current)
    }

    fun getWatchTime(channelId: String): Long {
        return _watchTimes.value[channelId] ?: 0L
    }

    /**
     * Program lookup with multi-level fallback matching:
     * 1. tvgId
     * 2. tvgName
     * 3. Channel Name
     * 4. Normalized name (cleaned of HD, 4K, symbols)
     */
    fun getProgramsForChannel(channel: IptvChannel): List<EpgProgram> {
        val map = _epgMap.value
        if (map.isEmpty()) return emptyList()

        channel.tvgId?.let { id ->
            map[id]?.let { if (it.isNotEmpty()) return it }
            map[id.lowercase()]?.let { if (it.isNotEmpty()) return it }
        }

        channel.tvgName?.let { name ->
            map[name]?.let { if (it.isNotEmpty()) return it }
            map[name.lowercase()]?.let { if (it.isNotEmpty()) return it }
        }

        map[channel.name]?.let { if (it.isNotEmpty()) return it }
        map[channel.name.lowercase()]?.let { if (it.isNotEmpty()) return it }

        val clean = channel.name.lowercase()
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
            .replace(Regex("\\b(hd|fhd|4k|uhd|sd|50fps|orig|hevc)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ]"), "")
            .trim()

        if (clean.isNotEmpty()) {
            map[clean]?.let { if (it.isNotEmpty()) return it }
        }

        return emptyList()
    }

    /**
     * Binary search for current program (O(log N))
     */
    fun getCurrentProgram(channel: IptvChannel): EpgProgram? {
        val progs = getProgramsForChannel(channel)
        if (progs.isEmpty()) return null
        val now = System.currentTimeMillis()

        val idx = progs.binarySearch { prog ->
            when {
                now < prog.startTime -> 1
                now >= prog.endTime -> -1
                else -> 0
            }
        }
        return if (idx >= 0) progs[idx] else null
    }

    /**
     * Returns the program airing right after the current one
     */
    fun getNextProgram(channel: IptvChannel): EpgProgram? {
        val progs = getProgramsForChannel(channel)
        if (progs.isEmpty()) return null
        val now = System.currentTimeMillis()

        val idx = progs.binarySearch { prog ->
            when {
                now < prog.startTime -> 1
                now >= prog.endTime -> -1
                else -> 0
            }
        }
        if (idx >= 0 && idx + 1 < progs.size) {
            return progs[idx + 1]
        }
        // Fallback: first program starting after now
        return progs.firstOrNull { it.startTime > now }
    }

    fun saveConfigAndLoad(playlistUrl: String, epgUrl: String?) {
        val cleanPlaylist = playlistUrl.trim()
        val cleanEpg = epgUrl?.trim()?.ifBlank { null }
        saveUrlsOnly(cleanPlaylist, cleanEpg)

        scope.launch {
            if (cleanPlaylist.isNotBlank()) {
                loadPlaylist(cleanPlaylist, cleanEpg)
            } else if (!cleanEpg.isNullOrBlank()) {
                fetchEpg(cleanEpg)
            }
        }
    }

    fun loadEpgOnly(epgUrl: String) {
        val cleanEpg = epgUrl.trim().ifBlank { return }
        prefs.edit().putString("iptv_epg_url", cleanEpg).apply()
        scope.launch {
            fetchEpg(cleanEpg)
        }
    }

    suspend fun loadFromLocalFile(file: File) = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        _isLoading.value = true
        _statusMessage.value = "Чтение файла ${file.name}..."
        try {
            val parseResult = FileInputStream(file).use { M3uParser.parse(it) }
            _channels.value = parseResult.channels
            saveCachedChannels(parseResult.channels)

            val detectedEpg = parseResult.epgUrl ?: getSavedEpgUrl()
            _statusMessage.value = "Каналов загружено: ${parseResult.channels.size}"

            if (!detectedEpg.isNullOrBlank()) {
                fetchEpg(detectedEpg)
            }
        } catch (e: Exception) {
            _statusMessage.value = "Ошибка чтения файла: ${e.localizedMessage ?: "Сбой"}"
        } finally {
            _isLoading.value = false
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
        _playlistProgress.value = 0f
        _statusMessage.value = "Загрузка плейлиста..."

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "RePlayer-TV")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                _statusMessage.value = "Ошибка загрузки: HTTP ${resp.code}"
                return@withContext
            }

            val body = resp.body
            if (body == null) {
                _statusMessage.value = "Пустой ответ сервера"
                return@withContext
            }

            val totalBytes = body.contentLength()
            val tempFile = File(context.cacheDir, "temp_playlist.m3u")
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val p = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            _playlistProgress.value = p
                        }
                    }
                }
            }
            _playlistProgress.value = 1f
            _statusMessage.value = "Обработка каналов плейлиста..."

            val parseResult = tempFile.inputStream().use { M3uParser.parse(it) }
            try { tempFile.delete() } catch (_: Exception) {}
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
            if (!manualEpgUrl.isNullOrBlank()) {
                try { fetchEpg(manualEpgUrl) } catch (_: Exception) {}
            }
        } finally {
            _isLoading.value = false
            _playlistProgress.value = null
        }
    }

    suspend fun fetchEpg(epgUrl: String) = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) return@withContext
        val targetUrl = normalizeEpgUrl(epgUrl)
        _epgProgress.value = 0f
        _statusMessage.value = "Загрузка телепрограммы (EPG)..."

        try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) RePlayer-TV")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                _statusMessage.value = "Ошибка EPG: HTTP ${resp.code}"
                return@withContext
            }

            val body = resp.body ?: return@withContext
            val totalBytes = body.contentLength()
            val tempFile = File(context.cacheDir, "temp_epg.xml")

            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val p = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            _epgProgress.value = p
                            _statusMessage.value = "Загрузка телепрограммы: ${(p * 100).toInt()}%"
                        }
                    }
                    output.flush()
                }
            }

            _epgProgress.value = 1f
            _statusMessage.value = "Распаковка и индексация телепрограммы..."

            val parsed = tempFile.inputStream().use { stream ->
                XmlTvParser.parse(stream)
            }
            try { tempFile.delete() } catch (_: Exception) {}
            _epgMap.value = parsed
            saveCachedEpg(parsed)
            _statusMessage.value = "Телепрограмма обновлена (${parsed.size} источников). Каналов: ${_channels.value.size}"
        } catch (e: Exception) {
            _statusMessage.value = "Сбой EPG: ${e.localizedMessage ?: "Сбой соединения"}"
        } finally {
            _epgProgress.value = null
        }
    }

    private fun loadFavoriteChannelIds(): Set<String> {
        return prefs.getStringSet("iptv_favorites", emptySet()) ?: emptySet()
    }

    private fun loadWatchTimes(): Map<String, Long> {
        val raw = prefs.getString("channel_watch_times", null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveWatchTimes(map: Map<String, Long>) {
        try {
            prefs.edit().putString("channel_watch_times", json.encodeToString(map)).apply()
        } catch (_: Exception) {}
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

    private fun loadCachedEpg(): Map<String, List<EpgProgram>> {
        return try {
            val file = File(context.cacheDir, "iptv_epg.json")
            if (file.exists()) {
                json.decodeFromString<Map<String, List<EpgProgram>>>(file.readText())
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCachedEpg(map: Map<String, List<EpgProgram>>) {
        try {
            // Keep top 300 active channels to prevent giant file
            val trimmed = map.entries.take(400).associate { it.key to it.value.take(40) }
            val file = File(context.cacheDir, "iptv_epg.json")
            file.writeText(json.encodeToString(trimmed))
        } catch (_: Exception) {}
    }
}
