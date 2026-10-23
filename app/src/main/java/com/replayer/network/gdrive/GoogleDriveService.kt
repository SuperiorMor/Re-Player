package com.replayer.network.gdrive

import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class GoogleDriveService(private val authManager: GoogleDriveAuthManager) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listFolder(folderId: String = "root"): List<MediaItem> = withContext(Dispatchers.IO) {
        val token = authManager.getSavedAccessToken() ?: return@withContext emptyList()
        val query = "'$folderId' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${query.replace(" ", "%20")}&fields=files(id,name,mimeType,size,thumbnailLink)&pageSize=100"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val items = mutableListOf<MediaItem>()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()

            val rootObj = json.parseToJsonElement(body).jsonObject
            val filesArray = rootObj["files"]?.jsonArray ?: return@withContext emptyList()

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
                    lowerName.endsWith(".webm") -> MediaType.VIDEO
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
                        parentId = folderId,
                        format = format
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        items
    }
}
