package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import com.replayer.diagnostics.CrashReporter
import com.replayer.model.EpgProgram
import com.replayer.model.IptvChannel
import com.replayer.network.iptv.M3uParser
import com.replayer.network.iptv.XmlTvParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
private data class CachedEpgData(
    val lists: Map<String, List<EpgProgram>>,
    val aliases: Map<String, String>
)

enum class ChannelSortMode(val label: String) {
    BY_WATCH_TIME("По просмотру (А-Я)"),
    BY_NAME("По названию (А-Я)"),
    DEFAULT("Как в плейлисте")
}

class IptvManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: IptvManager? = null

        fun getInstance(context: Context): IptvManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IptvManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

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

    private val _channels = MutableStateFlow<List<IptvChannel>>(emptyList())
    val channels: StateFlow<List<IptvChannel>> = _channels.asStateFlow()

    private val _favoriteChannelIds = MutableStateFlow<Set<String>>(loadFavoriteChannelIds())
    val favoriteChannelIds: StateFlow<Set<String>> = _favoriteChannelIds.asStateFlow()

    private val _epgMap = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
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

    private val _lastEpgUpdateText = MutableStateFlow(getEpgLastUpdatedText())
    val lastEpgUpdateText: StateFlow<String> = _lastEpgUpdateText.asStateFlow()

    private val _lastEpgUpdateTime = MutableStateFlow(getLastEpgUpdateTime())
    val lastEpgUpdateTime: StateFlow<Long> = _lastEpgUpdateTime.asStateFlow()

    @Volatile
    private var cachedLogosMap: Map<String, String> = emptyMap()

    private var isFetchingEpg = false

    init {
        scope.launch(Dispatchers.IO) {
            // 1. Asynchronously load disk caches off main thread
            val cachedCh = loadCachedChannels()
            _channels.value = cachedCh

            val cachedEpg = loadCachedEpg()
            _epgMap.value = cachedEpg

            cachedLogosMap = loadCachedLogos()
            CrashReporter.breadcrumb("IPTV", "Init cache: channels=${cachedCh.size}, epgKeys=${cachedEpg.size}")

            // 2. Wait 3 seconds so Compose UI renders frame 1 smoothly
            delay(3000)

            val savedPlaylist = getSavedPlaylistUrl()
            val savedLocalPath = prefs.getString("iptv_local_playlist_path", null)
            val savedEpg = getSavedEpgUrl()
            val lastUpdate = getLastEpgUpdateTime()
            val now = System.currentTimeMillis()
            val isEpgStale = lastUpdate > 0L && (now - lastUpdate) > 24 * 3600 * 1000L

            // Migration to M3U Parser v3 (#EXTGRP + catchup/tvg-rec archives)
            val parserVersion = prefs.getInt("m3u_parser_version", 1)
            val needsParserUpgrade = parserVersion < 3
            val epgDescVersion = prefs.getInt("epg_desc_version", 1)
            val needsEpgRefresh = epgDescVersion < 2

            if (needsParserUpgrade) {
                prefs.edit().putInt("m3u_parser_version", 3).putInt("epg_desc_version", 2).apply()
                if (!savedPlaylist.isNullOrBlank()) {
                    loadPlaylist(savedPlaylist, savedEpg)
                    return@launch
                } else if (!savedLocalPath.isNullOrBlank() && File(savedLocalPath).exists()) {
                    loadFromLocalFile(File(savedLocalPath))
                    return@launch
                } else if (!savedEpg.isNullOrBlank()) {
                    fetchEpg(savedEpg)
                    return@launch
                }
            } else if (needsEpgRefresh && !savedEpg.isNullOrBlank()) {
                prefs.edit().putInt("epg_desc_version", 2).apply()
                fetchEpg(savedEpg)
                return@launch
            } else if (needsEpgRefresh) {
                prefs.edit().putInt("epg_desc_version", 2).apply()
            }

            if (_channels.value.isEmpty() && !savedPlaylist.isNullOrBlank()) {
                loadPlaylist(savedPlaylist, savedEpg)
            } else if (!savedEpg.isNullOrBlank() && (_epgMap.value.isEmpty() || isEpgStale)) {
                fetchEpg(savedEpg)
            }
        }
    }

    fun getLastEpgUpdateTime(): Long = prefs.getLong("iptv_epg_last_updated", 0L)

    fun saveLastEpgUpdateTime(time: Long) {
        prefs.edit().putLong("iptv_epg_last_updated", time).commit()
        _lastEpgUpdateTime.value = time
        _lastEpgUpdateText.value = getEpgLastUpdatedText()
    }

    fun getEpgLastUpdatedText(): String {
        val last = prefs.getLong("iptv_epg_last_updated", 0L)
        if (last <= 0L) return ""
        val diffMs = System.currentTimeMillis() - last
        if (diffMs < 0) return "только что"
        val mins = diffMs / (60 * 1000L)
        if (mins < 1) return "только что"
        if (mins < 60) return "$mins мин. назад"
        val hours = mins / 60
        if (hours < 24) return "$hours ч. назад"
        val days = hours / 24
        return "$days дн. назад"
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
     * Puts generic "Общие"/"Другие" at the end.
     */
    fun getCategoryFolders(): List<String> {
        val groups = _channels.value
            .map { it.group }
            .distinct()
            .filter { it != "Все" && it != "Избранное" }
            .sortedWith(compareBy<String> { it == "Общие" || it == "Другие" }.thenBy { it.lowercase() })
        return groups.ifEmpty { listOf("Все каналы") }
    }

    fun getChannelsInFolder(folder: String): List<IptvChannel> {
        return if (folder == "Все каналы") {
            _channels.value
        } else {
            _channels.value.filter { it.group == folder }
        }
    }

    fun findChannelById(channelId: String): IptvChannel? {
        return _channels.value.find { it.id == channelId }
    }

    fun getSortedChannels(channels: List<IptvChannel>, sortMode: ChannelSortMode): List<IptvChannel> {
        return when (sortMode) {
            ChannelSortMode.DEFAULT -> channels
            ChannelSortMode.BY_NAME -> channels.sortedBy { it.name.lowercase() }
            ChannelSortMode.BY_WATCH_TIME -> {
                val times = _watchTimes.value
                channels.sortedWith(
                    compareByDescending<IptvChannel> { times[it.id] ?: 0L }
                        .thenBy { it.name.lowercase() }
                )
            }
        }
    }

    /**
     * Resolves playback URL for an archived/past EPG program based on M3U catchup tags
     * (catchup-source, flussonic, shift, append, or default tvg-rec -> ?utc=&lutc=).
     */
    fun buildArchiveUrl(channel: IptvChannel, program: EpgProgram): String? {
        if (!channel.hasArchive) return null

        val startSec = program.startTime / 1000L
        val endSec = program.endTime / 1000L
        val durationSec = ((program.endTime - program.startTime) / 1000L).coerceAtLeast(60L)
        val nowSec = System.currentTimeMillis() / 1000L
        val offsetSec = (nowSec - startSec).coerceAtLeast(0L)
        val base = channel.streamUrl.trim()

        val srcTemplate = channel.catchupSource
        if (!srcTemplate.isNullOrBlank()) {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = program.startTime
            }
            val year = String.format(java.util.Locale.US, "%04d", cal.get(java.util.Calendar.YEAR))
            val month = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.MONTH) + 1)
            val day = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.DAY_OF_MONTH))
            val hour = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.HOUR_OF_DAY))
            val minute = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.MINUTE))
            val second = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.SECOND))

            val resolved = srcTemplate
                .replace("{utc}", startSec.toString())
                .replace("\${start}", startSec.toString())
                .replace("{start}", startSec.toString())
                .replace("{lutc}", nowSec.toString())
                .replace("{now}", nowSec.toString())
                .replace("\${timestamp}", nowSec.toString())
                .replace("{utcend}", endSec.toString())
                .replace("\${end}", endSec.toString())
                .replace("{end}", endSec.toString())
                .replace("{duration}", durationSec.toString())
                .replace("\${duration}", durationSec.toString())
                .replace("{offset}", offsetSec.toString())
                .replace("\${offset}", offsetSec.toString())
                .replace("{Y}", year)
                .replace("{m}", month)
                .replace("{d}", day)
                .replace("{H}", hour)
                .replace("{M}", minute)
                .replace("{S}", second)

            if (resolved.startsWith("http://", ignoreCase = true) || resolved.startsWith("https://", ignoreCase = true)) {
                return resolved
            }
            return appendQueryOrPath(base, resolved)
        }

        val type = channel.catchupType?.lowercase()
        if (type == "flussonic" || type == "fs") {
            val qIdx = base.indexOf('?')
            val pathPart = if (qIdx != -1) base.substring(0, qIdx) else base
            val queryPart = if (qIdx != -1) base.substring(qIdx) else ""
            val flussonicArchiveName = if (program.isPast()) {
                "archive-$startSec-$durationSec.m3u8"
            } else {
                "timeshift_abs-$startSec.m3u8"
            }
            when {
                pathPart.endsWith("/index.m3u8", ignoreCase = true) ->
                    return pathPart.dropLast("index.m3u8".length) + flussonicArchiveName + queryPart
                pathPart.endsWith("/video.m3u8", ignoreCase = true) ->
                    return pathPart.dropLast("video.m3u8".length) + flussonicArchiveName + queryPart
                pathPart.endsWith("/mono.m3u8", ignoreCase = true) ->
                    return pathPart.dropLast("mono.m3u8".length) + flussonicArchiveName + queryPart
                pathPart.endsWith("/mpegts", ignoreCase = true) ->
                    return pathPart.dropLast("mpegts".length) + "timeshift_abs-$startSec.ts" + queryPart
            }
        }

        // Standard shift / default / tvg-rec catchup format
        return appendQueryOrPath(base, "?utc=$startSec&lutc=$nowSec")
    }

    private fun appendQueryOrPath(baseUrl: String, addition: String): String {
        val cleanAdd = addition.trim()
        if (cleanAdd.startsWith("?")) {
            return if (baseUrl.contains("?")) {
                "$baseUrl&${cleanAdd.removePrefix("?")}"
            } else {
                "$baseUrl$cleanAdd"
            }
        }
        if (cleanAdd.startsWith("&")) {
            return if (baseUrl.contains("?")) {
                "$baseUrl$cleanAdd"
            } else {
                "$baseUrl?${cleanAdd.removePrefix("&")}"
            }
        }
        return if (baseUrl.endsWith("/")) "$baseUrl$cleanAdd" else "$baseUrl/$cleanAdd"
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
            val cleanId = XmlTvParser.normalizeChannelName(id)
            if (cleanId.isNotEmpty()) {
                map[cleanId]?.let { if (it.isNotEmpty()) return it }
            }
        }

        channel.tvgName?.let { name ->
            map[name]?.let { if (it.isNotEmpty()) return it }
            map[name.lowercase()]?.let { if (it.isNotEmpty()) return it }
            val cleanName = XmlTvParser.normalizeChannelName(name)
            if (cleanName.isNotEmpty()) {
                map[cleanName]?.let { if (it.isNotEmpty()) return it }
            }
        }

        map[channel.name]?.let { if (it.isNotEmpty()) return it }
        map[channel.name.lowercase()]?.let { if (it.isNotEmpty()) return it }

        val clean = XmlTvParser.normalizeChannelName(channel.name)
        if (clean.isNotEmpty()) {
            map[clean]?.let { if (it.isNotEmpty()) return it }
            val noDigits = clean.replace(Regex("\\d+$"), "")
            if (noDigits.isNotEmpty() && noDigits != clean) {
                map[noDigits]?.let { if (it.isNotEmpty()) return it }
            }
        }

        return emptyList()
    }

    /**
     * Binary search with linear fallback for current program
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
        if (idx >= 0) return progs[idx]

        for (prog in progs) {
            if (now in prog.startTime until prog.endTime) {
                return prog
            }
        }

        return progs.firstOrNull { it.startTime > now }
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

    fun isEpgUrl(url: String): Boolean {
        val u = url.lowercase().trim()
        return u.endsWith(".xml") || u.endsWith(".xml.gz") || u.endsWith(".xml.xz") || u.endsWith(".xml.zip") ||
                u.contains("/epg2.xml") || u.contains("/epg.xml") || u.contains("epg.it999") || u.contains("epg.one")
    }

    fun isPlaylistUrl(url: String): Boolean {
        val u = url.lowercase().trim()
        return u.endsWith(".m3u") || u.endsWith(".m3u8") || u.contains(".m3u?") || u.contains(".m3u8?")
    }

    fun saveConfigAndLoad(playlistUrl: String, epgUrl: String?) {
        var cleanPlaylist = playlistUrl.trim()
        var cleanEpg = epgUrl?.trim()?.ifBlank { null }

        // Swap if user put EPG into playlist field and playlist was empty, or vice versa
        if (cleanPlaylist.isNotBlank() && isEpgUrl(cleanPlaylist) && cleanEpg == null) {
            cleanEpg = cleanPlaylist
            cleanPlaylist = ""
            _statusMessage.value = "Ссылка на EPG сохранена! Теперь добавьте плейлист каналов (.m3u) или выберите файл с флешки."
        } else if (cleanEpg != null && isPlaylistUrl(cleanEpg) && cleanPlaylist.isBlank()) {
            cleanPlaylist = cleanEpg
            cleanEpg = null
        }

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

    private fun lookupLogo(channel: IptvChannel, logos: Map<String, String>): String? {
        if (logos.isEmpty()) return null
        channel.tvgId?.let { id ->
            logos[id]?.let { return it }
            logos[id.lowercase()]?.let { return it }
            val cleanId = XmlTvParser.normalizeChannelName(id)
            if (cleanId.isNotEmpty()) logos[cleanId]?.let { return it }
        }
        channel.tvgName?.let { name ->
            logos[name]?.let { return it }
            logos[name.lowercase()]?.let { return it }
            val cleanName = XmlTvParser.normalizeChannelName(name)
            if (cleanName.isNotEmpty()) logos[cleanName]?.let { return it }
        }
        logos[channel.name]?.let { return it }
        logos[channel.name.lowercase()]?.let { return it }
        val clean = XmlTvParser.normalizeChannelName(channel.name)
        if (clean.isNotEmpty()) {
            logos[clean]?.let { return it }
            val noDigits = clean.replace(Regex("\\d+$"), "")
            if (noDigits.isNotEmpty() && noDigits != clean) {
                logos[noDigits]?.let { return it }
            }
        }
        return null
    }

    private fun applyLogosToChannels(channels: List<IptvChannel>, logos: Map<String, String>): List<IptvChannel> {
        if (logos.isEmpty()) return channels
        return channels.map { ch ->
            if (ch.logoUrl.isNullOrBlank()) {
                val found = lookupLogo(ch, logos)
                if (!found.isNullOrBlank()) ch.copy(logoUrl = found) else ch
            } else ch
        }
    }

    private fun buildMatchKeys(channels: List<IptvChannel>): Set<String> {
        val set = mutableSetOf<String>()
        for (ch in channels) {
            set.add(ch.id)
            set.add(ch.id.lowercase())
            set.add(ch.name.lowercase())
            ch.tvgId?.let {
                set.add(it)
                set.add(it.lowercase())
                val cleanId = XmlTvParser.normalizeChannelName(it)
                if (cleanId.isNotEmpty()) set.add(cleanId)
            }
            ch.tvgName?.let {
                set.add(it)
                set.add(it.lowercase())
                val cleanTvg = XmlTvParser.normalizeChannelName(it)
                if (cleanTvg.isNotEmpty()) set.add(cleanTvg)
            }
            val clean = XmlTvParser.normalizeChannelName(ch.name)
            if (clean.isNotEmpty()) set.add(clean)
        }
        return set
    }

    suspend fun loadFromLocalFile(file: File) = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        _isLoading.value = true
        _statusMessage.value = "Чтение файла ${file.name}..."
        try {
            prefs.edit()
                .putString("iptv_local_playlist_path", file.absolutePath)
                .putInt("m3u_parser_version", 3)
                .apply()

            val parseResult = FileInputStream(file).use { M3uParser.parse(it) }
            if (parseResult.channels.isNotEmpty()) {
                val withLogos = applyLogosToChannels(parseResult.channels, cachedLogosMap)
                _channels.value = withLogos
                saveCachedChannels(withLogos)

                val detectedEpg = parseResult.epgUrl ?: getSavedEpgUrl()
                _statusMessage.value = "Каналов загружено: ${withLogos.size}"

                if (!detectedEpg.isNullOrBlank()) {
                    fetchEpg(detectedEpg)
                }
            } else {
                _statusMessage.value = "В файле ${file.name} не найдено каналов"
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
            prefs.edit().putInt("m3u_parser_version", 3).apply()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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

            // Check if downloaded file is an EPG or gzip file instead of M3U
            val isGzipHeader = tempFile.length() >= 2 && try {
                tempFile.inputStream().use { it.read() == 0x1f && it.read() == 0x8b }
            } catch (_: Exception) { false }

            val startsWithXml = if (!isGzipHeader && tempFile.length() > 5) {
                try {
                    tempFile.bufferedReader().use { reader ->
                        val firstLine = reader.readLine() ?: ""
                        firstLine.startsWith("<?xml") || firstLine.contains("<tv")
                    }
                } catch (_: Exception) { false }
            } else false

            if (isGzipHeader || startsWithXml) {
                try { tempFile.delete() } catch (_: Exception) {}
                _statusMessage.value = "По ссылке передан EPG (телепрограмма), а не плейлист каналов (.m3u)! Перенесите ссылку в поле EPG."
                if (manualEpgUrl.isNullOrBlank()) {
                    prefs.edit().putString("iptv_epg_url", url).apply()
                    fetchEpg(url)
                }
                return@withContext
            }

            val parseResult = tempFile.inputStream().use { M3uParser.parse(it) }
            try { tempFile.delete() } catch (_: Exception) {}

            if (parseResult.channels.isEmpty()) {
                _statusMessage.value = "В файле не найдено каналов. Проверьте формат плейлиста (.m3u)."
                return@withContext
            }

            val withLogos = applyLogosToChannels(parseResult.channels, cachedLogosMap)
            _channels.value = withLogos
            saveCachedChannels(withLogos)

            val detectedOrManualEpg = manualEpgUrl ?: parseResult.epgUrl
            if (!detectedOrManualEpg.isNullOrBlank()) {
                prefs.edit().putString("iptv_epg_url", detectedOrManualEpg).apply()
                _statusMessage.value = "Каналов: ${withLogos.size}. Загрузка программы передач..."
                fetchEpg(detectedOrManualEpg)
            } else {
                _statusMessage.value = "Каналов загружено: ${withLogos.size}"
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
        if (epgUrl.isBlank() || isFetchingEpg) return@withContext
        isFetchingEpg = true
        CrashReporter.breadcrumb("IPTV", "fetchEpg start")
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
                            _statusMessage.value = "Загрузка архива телепрограммы: ${(p * 100).toInt()}%"
                        }
                    }
                    output.flush()
                }
            }

            _epgProgress.value = 1f
            _statusMessage.value = "Распаковка и индексация телепрограммы..."

            val currentChannels = _channels.value
            val targetSet = if (currentChannels.isNotEmpty()) buildMatchKeys(currentChannels) else null
            val archiveSet = if (currentChannels.isNotEmpty()) {
                buildMatchKeys(currentChannels.filter { it.hasArchive })
            } else null

            val parseResult = tempFile.inputStream().use { stream ->
                XmlTvParser.parse(
                    rawInputStream = stream,
                    targetChannels = targetSet,
                    archiveChannels = archiveSet
                ) { status ->
                    _statusMessage.value = status
                }
            }
            try { tempFile.delete() } catch (_: Exception) {}

            _epgMap.value = parseResult.programs
            saveCachedEpg(parseResult.programs)

            if (parseResult.logos.isNotEmpty()) {
                cachedLogosMap = parseResult.logos
                saveCachedLogos(parseResult.logos)
                val currentList = _channels.value
                if (currentList.isNotEmpty()) {
                    val updatedList = applyLogosToChannels(currentList, parseResult.logos)
                    if (updatedList != currentList) {
                        _channels.value = updatedList
                        saveCachedChannels(updatedList)
                    }
                }
            }

            saveLastEpgUpdateTime(System.currentTimeMillis())
            val totalProgs = parseResult.programs.values.sumOf { it.size }
            CrashReporter.breadcrumb("IPTV", "fetchEpg done: progs=$totalProgs")
            _statusMessage.value = "Телепрограмма обновлена: $totalProgs передач для ${_channels.value.size} каналов"
        } catch (e: Exception) {
            CrashReporter.breadcrumb("IPTV", "fetchEpg error: ${e.message}")
            _statusMessage.value = "Сбой EPG: ${e.localizedMessage ?: "Сбой соединения"}"
        } finally {
            _epgProgress.value = null
            isFetchingEpg = false
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
            val file = File(context.filesDir, "iptv_channels.json")
            if (file.exists()) {
                json.decodeFromString<List<IptvChannel>>(file.readText())
            } else {
                val oldFile = File(context.cacheDir, "iptv_channels.json")
                if (oldFile.exists()) {
                    val list = json.decodeFromString<List<IptvChannel>>(oldFile.readText())
                    saveCachedChannels(list)
                    list
                } else emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveCachedChannels(channels: List<IptvChannel>) {
        try {
            val file = File(context.filesDir, "iptv_channels.json")
            file.writeText(json.encodeToString(channels))
        } catch (_: Throwable) {}
    }

    private fun loadCachedEpg(): Map<String, List<EpgProgram>> {
        val gzFile = File(context.filesDir, "iptv_epg_v2.json.gz")
        if (gzFile.exists() && gzFile.length() in 1L..25_000_000L) {
            try {
                val rawText = GZIPInputStream(BufferedInputStream(FileInputStream(gzFile), 65536)).use { gz ->
                    gz.bufferedReader(Charsets.UTF_8).readText()
                }
                val cached = json.decodeFromString<CachedEpgData>(rawText)
                val result = HashMap<String, List<EpgProgram>>(cached.aliases.size + 16)
                for ((alias, groupId) in cached.aliases) {
                    val progs = cached.lists[groupId] ?: continue
                    if (progs.isNotEmpty()) {
                        result[alias] = progs
                    }
                }
                if (result.isNotEmpty()) {
                    return result
                }
            } catch (_: Throwable) {
                try { gzFile.delete() } catch (_: Throwable) {}
            }
        }

        // Legacy fallback from old uncompressed cache
        val legacyFile = File(context.filesDir, "iptv_epg.json")
        return try {
            if (legacyFile.exists() && legacyFile.length() in 1L..12_000_000L) {
                val loaded = json.decodeFromString<Map<String, List<EpgProgram>>>(legacyFile.readText())
                if (loaded.isNotEmpty()) {
                    saveCachedEpg(loaded)
                }
                loaded
            } else emptyMap()
        } catch (_: Throwable) {
            try { legacyFile.delete() } catch (_: Throwable) {}
            emptyMap()
        }
    }

    private fun saveCachedEpg(map: Map<String, List<EpgProgram>>) {
        try {
            val now = System.currentTimeMillis()
            val favMinT = now - 72L * 3600 * 1000L
            val favMaxT = now + 36L * 3600 * 1000L
            val genMinT = now - 3L * 3600 * 1000L
            val genMaxT = now + 18L * 3600 * 1000L

            val favChannels = getFavoriteChannels()
            val priorityKeys = mutableSetOf<String>()
            for (ch in favChannels) {
                priorityKeys.add(ch.id)
                priorityKeys.add(ch.id.lowercase())
                priorityKeys.add(ch.name)
                priorityKeys.add(ch.name.lowercase())
                ch.tvgId?.let {
                    priorityKeys.add(it)
                    priorityKeys.add(it.lowercase())
                }
                ch.tvgName?.let {
                    priorityKeys.add(it)
                    priorityKeys.add(it.lowercase())
                }
                val clean = XmlTvParser.normalizeChannelName(ch.name)
                if (clean.isNotEmpty()) priorityKeys.add(clean)
            }

            fun buildCachedPayload(stripNonFavDescriptions: Boolean): CachedEpgData {
                val lists = LinkedHashMap<String, List<EpgProgram>>()
                val aliases = LinkedHashMap<String, String>()
                val listToGroupId = IdentityHashMap<List<EpgProgram>, String>()
                val listIsFav = IdentityHashMap<List<EpgProgram>, Boolean>()

                for ((key, rawList) in map) {
                    if (rawList.isEmpty()) continue
                    if (priorityKeys.contains(key)) {
                        listIsFav[rawList] = true
                    }
                }

                var counter = 0
                for ((key, rawList) in map) {
                    if (rawList.isEmpty()) continue
                    var groupId = listToGroupId[rawList]
                    if (groupId == null) {
                        val isFav = listIsFav[rawList] == true || priorityKeys.contains(key)
                        val minT = if (isFav) favMinT else genMinT
                        val maxT = if (isFav) favMaxT else genMaxT
                        val filtered = rawList
                            .filter { it.endTime >= minT && it.startTime <= maxT }
                            .let { list ->
                                if (stripNonFavDescriptions && !isFav) {
                                    list.map { if (it.description != null) it.copy(description = null) else it }
                                } else list
                            }
                        if (filtered.isEmpty()) continue
                        groupId = "g$counter"
                        counter++
                        listToGroupId[rawList] = groupId
                        lists[groupId] = filtered
                    }
                    aliases[key] = groupId
                }
                return CachedEpgData(lists = lists, aliases = aliases)
            }

            fun writeGzip(payload: CachedEpgData, targetFile: File): Long {
                val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 65536)).use { gz ->
                    gz.write(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
                }
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile)
                return targetFile.length()
            }

            val gzFile = File(context.filesDir, "iptv_epg_v2.json.gz")
            val writtenBytes = writeGzip(buildCachedPayload(stripNonFavDescriptions = false), gzFile)
            if (writtenBytes > 10_000_000L) {
                writeGzip(buildCachedPayload(stripNonFavDescriptions = true), gzFile)
            }

            // Clean up legacy uncompressed cache if present
            val legacyFile = File(context.filesDir, "iptv_epg.json")
            if (legacyFile.exists()) {
                try { legacyFile.delete() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun loadCachedLogos(): Map<String, String> {
        val file = File(context.filesDir, "iptv_epg_logos.json")
        return try {
            if (file.exists() && file.length() <= 1_500_000L) {
                json.decodeFromString<Map<String, String>>(file.readText())
            } else emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun saveCachedLogos(logos: Map<String, String>) {
        try {
            val trimmed = logos.entries.take(4000).associate { it.key to it.value }
            val file = File(context.filesDir, "iptv_epg_logos.json")
            file.writeText(json.encodeToString(trimmed))
        } catch (_: Throwable) {}
    }
}
