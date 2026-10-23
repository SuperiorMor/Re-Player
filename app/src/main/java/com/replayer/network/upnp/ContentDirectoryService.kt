package com.replayer.network.upnp

import android.util.Log
import android.util.Xml
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class ContentDirectoryService {
    private val client = OkHttpClient()

    suspend fun browse(controlUrl: String, objectId: String = "0"): List<MediaItem> = withContext(Dispatchers.IO) {
        val soapAction = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
        val soapBody = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body>")
            append("<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">")
            append("<ObjectID>$objectId</ObjectID>")
            append("<BrowseFlag>BrowseDirectChildren</BrowseFlag>")
            append("<Filter>*</Filter>")
            append("<StartingIndex>0</StartingIndex>")
            append("<RequestedCount>100</RequestedCount>")
            append("<SortCriteria></SortCriteria>")
            append("</u:Browse>")
            append("</s:Body>")
            append("</s:Envelope>")
        }

        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPAction", soapAction)
            .post(soapBody.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        val items = mutableListOf<MediaItem>()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val rawXml = response.body?.string() ?: return@withContext emptyList()

            val resultXml = extractResultTag(rawXml) ?: return@withContext emptyList()
            parseDidlLite(resultXml, items, objectId)
        } catch (e: Exception) {
            Log.e("ContentDirectoryService", "Browse error for $controlUrl", e)
        }

        items
    }

    private fun extractResultTag(soapXml: String): String? {
        val startIdx = soapXml.indexOf("<Result")
        if (startIdx == -1) return null
        val tagCloseIdx = soapXml.indexOf(">", startIdx)
        if (tagCloseIdx == -1) return null
        val endIdx = soapXml.indexOf("</Result>", tagCloseIdx)
        if (endIdx == -1) return null

        val encoded = soapXml.substring(tagCloseIdx + 1, endIdx)
        return encoded.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun parseDidlLite(didlXml: String, items: MutableList<MediaItem>, parentId: String) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(didlXml))

        var eventType = parser.eventType
        var currentItem: MutableMap<String, String>? = null
        val textBuffer = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val rawName = parser.name ?: ""
                    val localTag = rawName.substringAfterLast(':').lowercase()
                    textBuffer.clear()

                    if (localTag == "container" || localTag == "item") {
                        currentItem = mutableMapOf()
                        currentItem["isContainer"] = if (localTag == "container") "true" else "false"
                        currentItem["id"] = parser.getAttributeValue(null, "id") ?: ""
                    }
                }
                XmlPullParser.TEXT -> {
                    textBuffer.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    val rawName = parser.name ?: ""
                    val localTag = rawName.substringAfterLast(':').lowercase()
                    val text = textBuffer.toString().trim()

                    if (currentItem != null && text.isNotEmpty()) {
                        when (localTag) {
                            "title" -> currentItem["title"] = text
                            "res" -> currentItem["res"] = text
                            "class" -> currentItem["class"] = text
                            "albumarturi" -> currentItem["albumArt"] = text
                        }
                    }

                    if ((localTag == "container" || localTag == "item") && currentItem != null) {
                        val isContainer = currentItem["isContainer"] == "true"
                        val upnpClass = currentItem["class"] ?: ""
                        val title = currentItem["title"]?.trim()?.ifEmpty { "Unnamed" } ?: "Unnamed"
                        val id = currentItem["id"] ?: ""
                        val res = currentItem["res"] ?: ""

                        val mediaType = when {
                            isContainer -> MediaType.DIRECTORY
                            upnpClass.contains("videoItem", ignoreCase = true) -> MediaType.VIDEO
                            upnpClass.contains("audioItem", ignoreCase = true) -> MediaType.AUDIO
                            upnpClass.contains("imageItem", ignoreCase = true) -> MediaType.IMAGE
                            res.endsWith(".mkv", ignoreCase = true) || res.endsWith(".mp4", ignoreCase = true) ||
                            res.endsWith(".avi", ignoreCase = true) || res.endsWith(".ts", ignoreCase = true) ||
                            res.endsWith(".m2ts", ignoreCase = true) || res.endsWith(".mov", ignoreCase = true) ||
                            res.endsWith(".webm", ignoreCase = true) -> MediaType.VIDEO
                            res.endsWith(".mp3", ignoreCase = true) || res.endsWith(".flac", ignoreCase = true) ||
                            res.endsWith(".m4a", ignoreCase = true) || res.endsWith(".wav", ignoreCase = true) ||
                            res.endsWith(".aac", ignoreCase = true) || res.endsWith(".ogg", ignoreCase = true) -> MediaType.AUDIO
                            res.endsWith(".jpg", ignoreCase = true) || res.endsWith(".jpeg", ignoreCase = true) ||
                            res.endsWith(".png", ignoreCase = true) || res.endsWith(".webp", ignoreCase = true) -> MediaType.IMAGE
                            else -> MediaType.VIDEO
                        }

                        items.add(
                            MediaItem(
                                id = id,
                                title = title,
                                uri = res,
                                type = mediaType,
                                sourceType = MediaSourceType.UPNP_DLNA,
                                parentId = parentId
                            )
                        )
                        currentItem = null
                    }
                    textBuffer.clear()
                }
            }
            eventType = parser.next()
        }
    }
}
