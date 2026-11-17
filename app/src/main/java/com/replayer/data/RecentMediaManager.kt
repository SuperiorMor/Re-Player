package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.MediaType
import com.replayer.model.RecentMedia
import com.replayer.network.upnp.ContentDirectoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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

    private val listLock = Any()
    private val attemptedPosterKeys = HashSet<String>()
    private val scanningKeys = HashSet<String>()
    private val previewPosterCache = ConcurrentHashMap<String, String>()
    private val folderLastOpened: MutableMap<String, String> = loadFolderLastOpened()

    private fun loadFolderLastOpened(): MutableMap<String, String> {
        val raw = prefs.getString("folder_last_opened_json", null) ?: return mutableMapOf()
        return try {
            json.decodeFromString<Map<String, String>>(raw).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveFolderLastOpenedLocked() {
        try {
            if (folderLastOpened.size > 400) {
                val keysToDrop = folderLastOpened.keys.take(folderLastOpened.size - 400)
                keysToDrop.forEach { folderLastOpened.remove(it) }
            }
            prefs.edit().putString("folder_last_opened_json", json.encodeToString(folderLastOpened.toMap())).apply()
        } catch (_: Exception) {}
    }

    private fun recordFolderSelectionLocked(folderId: String?, itemId: String, itemUri: String, itemTitle: String = "") {
        val cleanFolder = folderId?.ifBlank { null } ?: return
        if (itemId.isBlank()) return
        // Re-insert so LinkedHashMap preserves most-recently-used ordering at the tail
        folderLastOpened.remove(cleanFolder)
        folderLastOpened[cleanFolder] = "$itemId\n$itemUri\n$itemTitle"
        saveFolderLastOpenedLocked()
    }

    fun recordFolderSelection(folderId: String?, itemId: String, itemUri: String, itemTitle: String = "") {
        synchronized(listLock) {
            recordFolderSelectionLocked(folderId, itemId, itemUri, itemTitle)
        }
    }

    fun getLastOpenedKeyInFolder(folderId: String?): Triple<String, String, String>? {
        val cleanFolder = folderId?.ifBlank { null } ?: return null
        val stored = synchronized(listLock) { folderLastOpened[cleanFolder] } ?: return null
        val parts = stored.split('\n', limit = 3)
        val id = parts.getOrNull(0)?.ifBlank { null } ?: return null
        val uri = parts.getOrNull(1) ?: ""
        val title = parts.getOrNull(2) ?: ""
        return Triple(id, uri, title)
    }

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
        synchronized(listLock) {
            val current = _recentList.value.toMutableList()
            val cleanParent = parentId?.ifBlank { null }
            val sameExact = current.find { it.id == id || it.uri == uri }
            val sameFolder = if (cleanParent != null) current.find { it.parentId == cleanParent } else null

            val savedProg = _progressMap.value[id] ?: _progressMap.value[uri]
            val pos = sameExact?.positionMs?.takeIf { it > 0L } ?: savedProg?.positionMs ?: 0L
            val dur = sameExact?.durationMs?.takeIf { it > 0L } ?: savedProg?.durationMs ?: 0L

            val resolvedThumb = thumbnailUrl?.ifBlank { null }
                ?: sameExact?.thumbnailUrl?.ifBlank { null }

            // Deduplicate: keep only ONE entry per folder (or per item if parentId unknown)
            current.removeAll { item ->
                item.id == id || item.uri == uri || (cleanParent != null && item.parentId == cleanParent)
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
                    parentId = cleanParent,
                    folderTitle = folderTitle ?: sameFolder?.folderTitle
                )
            )
            // Remember last played file inside its DLNA folder for smart cursor restoration
            if (cleanParent != null) {
                recordFolderSelectionLocked(cleanParent, id, uri, title)
            }
            // Limit section strictly to 2 folders
            saveRecent(current.take(2))
        }
    }

    fun updateThumbnail(id: String, uri: String, thumbnailUrl: String, parentId: String? = null) {
        synchronized(listLock) {
            val current = _recentList.value.toMutableList()
            var changed = false
            val cleanParent = parentId?.ifBlank { null }
            for (i in current.indices) {
                val item = current[i]
                if (item.id == id || item.uri == uri) {
                    if (item.thumbnailUrl != thumbnailUrl) {
                        current[i] = item.copy(thumbnailUrl = thumbnailUrl)
                        changed = true
                    }
                }
            }
            if (changed) {
                saveRecent(current)
            }
        }
    }

    fun getCachedPreviewPoster(itemId: String): String? =
        previewPosterCache[itemId]?.ifBlank { null }

    fun hasCachedPreviewPoster(itemId: String): Boolean =
        previewPosterCache.containsKey(itemId)

    /**
     * Resolves a vertical poster preview for DLNA list/folder inspection with memory caching.
     * Generic top-level category folders (e.g. "Видео", "Фильмы") skip online lookups to avoid false hits.
     */
    suspend fun resolvePreviewPosterForItem(
        item: MediaItem,
        folderItems: List<MediaItem>,
        controlUrl: String? = null,
        folderTitle: String? = null
    ): String? {
        previewPosterCache[item.id]?.let { return it.ifBlank { null } }

        return withContext(Dispatchers.IO) {
            previewPosterCache[item.id]?.let { return@withContext it.ifBlank { null } }

            val resolved: String? = when (item.type) {
                MediaType.IMAGE -> item.uri.ifBlank { item.thumbnailUrl?.ifBlank { null } }
                MediaType.AUDIO -> item.thumbnailUrl?.ifBlank { null }
                MediaType.DIRECTORY -> {
                    val lower = item.title.trim().lowercase()
                    if (isGenericCategoryFolder(lower)) {
                        item.thumbnailUrl?.ifBlank { null }
                    } else {
                        val isSeasonSub = Regex("^(season|sezon|сезон|s\\d+|part|часть)\\s*\\d*$", RegexOption.IGNORE_CASE)
                            .matches(item.title.trim())
                        val effectiveTitle = if (isSeasonSub && !folderTitle.isNullOrBlank() && !isGenericCategoryFolder(folderTitle.trim().lowercase())) {
                            folderTitle
                        } else {
                            item.title
                        }
                        val subItems = if (!controlUrl.isNullOrBlank()) {
                            try {
                                ContentDirectoryService().browse(controlUrl, item.id)
                            } catch (_: Throwable) {
                                emptyList()
                            }
                        } else emptyList()

                        findVerticalPosterCascade(
                            targetItem = item,
                            folderItems = subItems,
                            controlUrl = controlUrl,
                            folderTitle = effectiveTitle
                        )
                    }
                }
                MediaType.VIDEO, MediaType.UNKNOWN -> {
                    // Instant reuse if already resolved for Continue Watching
                    val fromRecent = _recentList.value.firstOrNull {
                        (it.id == item.id || it.uri == item.uri) &&
                            !it.thumbnailUrl.isNullOrBlank()
                    }?.thumbnailUrl
                    if (!fromRecent.isNullOrBlank()) {
                        fromRecent
                    } else {
                        val effectiveFolder = folderTitle?.takeIf { !isGenericCategoryFolder(it.trim().lowercase()) }
                        findVerticalPosterCascade(
                            targetItem = item,
                            folderItems = folderItems,
                            controlUrl = controlUrl,
                            folderTitle = effectiveFolder
                        )
                    }
                }
            }

            previewPosterCache[item.id] = resolved ?: ""
            resolved
        }
    }

    private fun isGenericCategoryFolder(lowerTitle: String): Boolean {
        if (lowerTitle.length <= 2) return true
        val generic = setOf(
            "видео", "фильмы", "сериалы", "папки", "фото", "изображения", "картинки",
            "музыка", "аудио", "все видео", "все файлы", "все фото", "недавнее", "недавние",
            "новые", "добавленные", "избранное", "загрузки", "торренты", "downloads",
            "video", "videos", "movies", "films", "tv", "series", "folders", "by folder",
            "all video", "all files", "all", "photos", "pictures", "images", "music", "audio",
            "recent", "recently added", "favorites"
        )
        return generic.contains(lowerTitle)
    }

    /**
     * Searches files in the same DLNA folder (and across image branches of the DLNA server,
     * with online artwork fallback) for a vertical (portrait, height > width) poster.
     */
    fun resolveVerticalPoster(
        targetItem: MediaItem,
        folderItems: List<MediaItem>,
        controlUrl: String? = null,
        folderTitle: String? = null
    ) {
        scope.launch {
            val posterUrl = findVerticalPosterCascade(
                targetItem = targetItem,
                folderItems = folderItems,
                controlUrl = controlUrl,
                folderTitle = folderTitle
            )
            if (posterUrl != null) {
                updateThumbnail(targetItem.id, targetItem.uri, posterUrl, targetItem.parentId)
            }
        }
    }

    /**
     * Scans recent items missing a thumbnail (on HomeScreen start or when UPnP device connects)
     * to resolve vertical posters via DLNA folder, DLNA image/search branches, or online poster fallback.
     */
    fun resolveMissingPosters(controlUrl: String?) {
        val targets = _recentList.value.filter { recent ->
            recent.thumbnailUrl.isNullOrBlank()
        }
        if (targets.isEmpty()) return

        val cds = ContentDirectoryService()
        for (recent in targets) {
            val pId = recent.parentId?.ifBlank { null }
            val cacheKey = "${recent.id}|${pId.orEmpty()}|${controlUrl.orEmpty()}"
            val shouldScan = synchronized(listLock) {
                if (attemptedPosterKeys.contains(cacheKey) || scanningKeys.contains(cacheKey)) {
                    false
                } else {
                    scanningKeys.add(cacheKey)
                    true
                }
            }
            if (!shouldScan) continue

            scope.launch {
                try {
                    val folderItems = if (!controlUrl.isNullOrBlank() && pId != null) {
                        cds.browse(controlUrl, pId)
                    } else {
                        emptyList()
                    }
                    val targetAsMedia = MediaItem(
                        id = recent.id,
                        title = recent.title,
                        uri = recent.uri,
                        type = MediaType.VIDEO,
                        sourceType = MediaSourceType.UPNP_DLNA,
                        parentId = pId
                    )
                    val posterUrl = findVerticalPosterCascade(
                        targetItem = targetAsMedia,
                        folderItems = folderItems,
                        controlUrl = controlUrl,
                        folderTitle = recent.folderTitle
                    )
                    if (posterUrl != null) {
                        updateThumbnail(recent.id, recent.uri, posterUrl, pId)
                    }
                    synchronized(listLock) {
                        attemptedPosterKeys.add(cacheKey)
                    }
                } catch (_: Throwable) {
                } finally {
                    synchronized(listLock) {
                        scanningKeys.remove(cacheKey)
                    }
                }
            }
        }
    }

    private suspend fun findVerticalPosterCascade(
        targetItem: MediaItem,
        folderItems: List<MediaItem>,
        controlUrl: String?,
        folderTitle: String?
    ): String? {
        // 1. Direct server-provided albumArtURI on the video item itself (if provided directly by DLNA server)
        val art = targetItem.thumbnailUrl
        if (!art.isNullOrBlank() && isVerticalImage(art, null)) {
            return art
        }

        // 2. Fallback: fetch clean vertical poster online (Kinopoisk / TVMaze / iTunes / OMDb)
        return findOnlineVerticalPoster(folderTitle, targetItem.title)
    }

    private fun extractCleanMediaTitle(rawName: String): String {
        var s = rawName.trim()
        // Strip file extension if present
        s = s.replace(Regex("\\.(avi|mkv|mp4|mov|ts|m2ts|webm|wmv|flv|mpeg|mpg|m4v)$", RegexOption.IGNORE_CASE), "")
        // Normalize separators
        s = s.replace('.', ' ').replace('_', ' ')
        // Cut at season/episode, year, or release tags
        s = s.replace(Regex("\\s+(?:S\\d{1,2}(?:E\\d{1,3})?|\\d{1,2}x\\d{1,2}|Season\\s*\\d+|Сезон\\s*\\d+|(?:19|20)\\d{2}|WEB[- ]?DL(?:Rip)?|HDRip|BDRip|BluRay|DVDRip|HDTV|1080p|720p|2160p|4K|x264|x265|HEVC|H\\.?264|H\\.?265|XviD|AC3|DTS|RUS|ENG|LF|RGzsRutracker).*$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\[.*?\\]|\\(.*?\\)"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun findOnlineVerticalPoster(folderTitle: String?, fileTitle: String): String? {
        val candidates = listOfNotNull(folderTitle, fileTitle)
            .map { extractCleanMediaTitle(it) }
            .filter { it.length >= 2 }
            .distinct()

        for (query in candidates) {
            // 1. TVMaze (great for TV series like "The Pitt")
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val req = Request.Builder()
                    .url("https://api.tvmaze.com/singlesearch/shows?q=$encoded")
                    .header("User-Agent", "RePlayer/1.0 (Android TV)")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val jsonObj = JSONObject(body)
                            val imgObj = jsonObj.optJSONObject("image")
                            val posterUrl = imgObj?.optString("original")?.ifBlank { null }
                                ?: imgObj?.optString("medium")?.ifBlank { null }
                            if (!posterUrl.isNullOrBlank() && isVerticalImage(posterUrl, null)) {
                                return posterUrl
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}

            // 2. iTunes Search API (works for both movies and TV shows, including Russian titles)
            val hasCyrillic = query.any { it in '\u0400'..'\u04FF' }
            val countryParam = if (hasCyrillic) "&country=ru&lang=ru_ru" else ""
            val mediaVariants = listOf("tvShow&entity=tvSeason", "movie&entity=movie")
            for (mediaType in mediaVariants) {
                try {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val req = Request.Builder()
                        .url("https://itunes.apple.com/search?term=$encoded&media=$mediaType$countryParam&limit=3")
                        .header("User-Agent", "RePlayer/1.0 (Android TV)")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                val results = JSONObject(body).optJSONArray("results")
                                if (results != null) {
                                    for (i in 0 until results.length()) {
                                        val art100 = results.getJSONObject(i).optString("artworkUrl100").ifBlank { null } ?: continue
                                        val hiRes = art100.replace("100x100bb", "600x600bb")
                                        if (isVerticalImage(hiRes, null)) {
                                            return hiRes
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun isLikelyImageByNameOrUri(title: String, uri: String): Boolean {
        val t = title.lowercase()
        val u = uri.substringBefore('?').substringBefore('#').lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif").any {
            t.endsWith(it) || u.endsWith(it)
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
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "RePlayer/1.0 (Android TV)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val stream = resp.body?.byteStream() ?: return false
                val maxHeaderBytes = 262144
                val buffer = ByteArray(maxHeaderBytes)
                var totalRead = 0
                while (totalRead < maxHeaderBytes) {
                    val read = stream.read(buffer, totalRead, maxHeaderBytes - totalRead)
                    if (read <= 0) break
                    totalRead += read

                    // Direct PNG IHDR header check (first 24 bytes: width at 16..19, height at 20..23)
                    if (totalRead >= 24 && isPngSignature(buffer)) {
                        val w = readIntBE(buffer, 16)
                        val h = readIntBE(buffer, 20)
                        if (w > 0 && h > 0) return h > w
                    }

                    // Direct JPEG / WEBP / GIF bounds check via BitmapFactory once enough bytes buffered
                    if (totalRead >= 16384) {
                        val quickOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(buffer, 0, totalRead, quickOpts)
                        if (quickOpts.outWidth > 0 && quickOpts.outHeight > 0) {
                            return quickOpts.outHeight > quickOpts.outWidth
                        }
                    }
                }
                if (totalRead <= 0) return false
                if (totalRead >= 24 && isPngSignature(buffer)) {
                    val w = readIntBE(buffer, 16)
                    val h = readIntBE(buffer, 20)
                    if (w > 0 && h > 0) return h > w
                }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(buffer, 0, totalRead, opts)
                opts.outWidth > 0 && opts.outHeight > opts.outWidth
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPngSignature(bytes: ByteArray): Boolean {
        return bytes.size >= 24 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte() &&
            bytes[12] == 0x49.toByte() && // 'I'
            bytes[13] == 0x48.toByte() && // 'H'
            bytes[14] == 0x44.toByte() && // 'D'
            bytes[15] == 0x52.toByte()    // 'R'
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
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
