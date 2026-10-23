package com.replayer.network.gdrive

import android.util.Log
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GoogleDriveService(private val authManager: GoogleDriveAuthManager) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    var lastErrorMessage: String? = null
        private set

    suspend fun getOrCreateRePlayerFolder(token: String): MediaItem? = withContext(Dispatchers.IO) {
        val searchUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "mimeType = 'application/vnd.google-apps.folder' and name = 'Re:Player' and trashed = false")
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("includeItemsFromAllDrives", "true")
            .build()

        val searchReq = Request.Builder()
            .url(searchUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        try {
            val response = client.newCall(searchReq).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val rootObj = json.parseToJsonElement(body).jsonObject
                val files = rootObj["files"]?.jsonArray
                if (files != null && files.isNotEmpty()) {
                    val folderObj = files[0].jsonObject
                    val id = folderObj["id"]?.jsonPrimitive?.content ?: ""
                    if (id.isNotBlank()) {
                        return@withContext MediaItem(
                            id = id,
                            title = "Re:Player",
                            uri = "",
                            type = MediaType.DIRECTORY,
                            sourceType = MediaSourceType.GOOGLE_DRIVE,
                            parentId = "root"
                        )
                    }
                }
            }

            // Folder does not exist, create it
            val createUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("supportsAllDrives", "true")
                .build()

            val jsonBody = """{"name":"Re:Player","mimeType":"application/vnd.google-apps.folder"}"""
            val reqBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val createReq = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .post(reqBody)
                .build()

            val createResp = client.newCall(createReq).execute()
            val createBody = createResp.body?.string() ?: ""
            if (createResp.isSuccessful) {
                val createdObj = json.parseToJsonElement(createBody).jsonObject
                val id = createdObj["id"]?.jsonPrimitive?.content ?: ""
                if (id.isNotBlank()) {
                    return@withContext MediaItem(
                        id = id,
                        title = "Re:Player",
                        uri = "",
                        type = MediaType.DIRECTORY,
                        sourceType = MediaSourceType.GOOGLE_DRIVE,
                        parentId = "root"
                    )
                }
            } else {
                Log.e("DriveService", "Create folder error (${createResp.code}): $createBody")
            }
        } catch (e: Exception) {
            Log.e("DriveService", "Error getOrCreateRePlayerFolder: ${e.message}", e)
        }
        null
    }

    suspend fun listFolder(folderId: String = "root"): List<MediaItem> = withContext(Dispatchers.IO) {
        lastErrorMessage = null
        var token = authManager.getSavedAccessToken()
        if (token.isNullOrBlank()) {
            lastErrorMessage = "Требуется авторизация"
            return@withContext emptyList()
        }

        val isRoot = folderId == "root"
        val primaryQuery = if (isRoot) {
            "'root' in parents and trashed = false"
        } else {
            "'$folderId' in parents and trashed = false"
        }

        var items = executeDriveQuery(token, primaryQuery, folderId).toMutableList()

        // If token was expired (401), try refreshing token and re-execute
        if (lastErrorMessage?.contains("401") == true) {
            val newToken = authManager.refreshAccessToken()
            if (!newToken.isNullOrBlank()) {
                token = newToken
                lastErrorMessage = null
                items = executeDriveQuery(token, primaryQuery, folderId).toMutableList()
            }
        }

        // If root has no items with "'root' in parents", fallback to broad query for accessible files/folders
        if (isRoot && items.isEmpty() && lastErrorMessage == null) {
            val broadItems = executeDriveQuery(token, "trashed = false", folderId)
            val existingIds = items.map { it.id }.toSet()
            for (item in broadItems) {
                if (item.id !in existingIds) {
                    items.add(item)
                }
            }
        }

        items
    }

    private fun executeDriveQuery(token: String, query: String, parentFolderId: String): List<MediaItem> {
        val httpUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name,mimeType,size,thumbnailLink,parents)")
            .addQueryParameter("pageSize", "100")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("includeItemsFromAllDrives", "true")
            .build()

        val request = Request.Builder()
            .url(httpUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val items = mutableListOf<MediaItem>()
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e("DriveService", "Drive API error (${response.code}): $body")
                lastErrorMessage = "Ошибка Google Drive (${response.code})"
                return emptyList()
            }

            val rootObj = json.parseToJsonElement(body).jsonObject
            val filesArray = rootObj["files"]?.jsonArray ?: return emptyList()

            for (element in filesArray) {
                val f = element.jsonObject
                val id = f["id"]?.jsonPrimitive?.content ?: continue
                val name = f["name"]?.jsonPrimitive?.content ?: "Unnamed"
                val mimeType = f["mimeType"]?.jsonPrimitive?.content ?: ""
                val size = f["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val thumb = f["thumbnailLink"]?.jsonPrimitive?.content

                val isFolder = mimeType == "application/vnd.google-apps.folder"
                val lowerName = name.lowercase()
                val mediaType = when {
                    isFolder -> MediaType.DIRECTORY
                    mimeType.startsWith("video/") -> MediaType.VIDEO
                    mimeType.startsWith("audio/") -> MediaType.AUDIO
                    mimeType.startsWith("image/") -> MediaType.IMAGE
                    lowerName.endsWith(".mkv") || lowerName.endsWith(".avi") || lowerName.endsWith(".mp4") ||
                    lowerName.endsWith(".mov") || lowerName.endsWith(".ts") || lowerName.endsWith(".m2ts") ||
                    lowerName.endsWith(".webm") || lowerName.endsWith(".flv") || lowerName.endsWith(".wmv") -> MediaType.VIDEO
                    lowerName.endsWith(".mp3") || lowerName.endsWith(".flac") || lowerName.endsWith(".wav") ||
                    lowerName.endsWith(".m4a") || lowerName.endsWith(".aac") || lowerName.endsWith(".ogg") -> MediaType.AUDIO
                    lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
                    lowerName.endsWith(".webp") -> MediaType.IMAGE
                    else -> continue
                }

                val ext = name.substringAfterLast('.', "").uppercase()
                val format = if (ext.isNotEmpty() && ext.length <= 5) ext else null

                // Streaming URL with alt=media
                val streamUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"

                items.add(
                    MediaItem(
                        id = id,
                        title = name,
                        uri = streamUrl,
                        type = mediaType,
                        sourceType = MediaSourceType.GOOGLE_DRIVE,
                        size = size,
                        mimeType = mimeType,
                        thumbnailUrl = thumb,
                        parentId = parentFolderId,
                        format = format
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("DriveService", "Exception in executeDriveQuery: ${e.message}", e)
            lastErrorMessage = e.localizedMessage ?: "Ошибка сети"
        }
        return items
    }
}
