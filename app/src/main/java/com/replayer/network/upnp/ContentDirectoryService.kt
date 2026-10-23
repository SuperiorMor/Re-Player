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
        val start = soapXml.indexOf("<Result>")
        val end = soapXml.indexOf("</Result>")
        if (start != -1 && end != -1) {
            val encoded = soapXml.substring(start + 8, end)
            return encoded.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
        }
        return null
    }

    private fun parseDidlLite(didlXml: String, items: MutableList<MediaItem>, parentId: String) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(didlXml))

        var eventType = parser.eventType
        var currentItem: MutableMap<String, String>? = null
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "container" || currentTag == "item") {
                        currentItem = mutableMapOf()
                        currentItem["isContainer"] = if (currentTag == "container") "true" else "false"
                        currentItem["id"] = parser.getAttributeValue(null, "id") ?: ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty() && currentItem != null) {
                        when (currentTag) {
                            "dc:title" -> currentItem["title"] = text
                            "res" -> currentItem["res"] = text
                            "upnp:class" -> currentItem["class"] = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((parser.name == "container" || parser.name == "item") && currentItem != null) {
                        val isContainer = currentItem["isContainer"] == "true"
                        val upnpClass = currentItem["class"] ?: ""
                        val title = currentItem["title"] ?: "Unnamed"
                        val id = currentItem["id"] ?: ""
                        val res = currentItem["res"] ?: ""

                        val mediaType = when {
                            isContainer -> MediaType.DIRECTORY
                            upnpClass.contains("videoItem") -> MediaType.VIDEO
                            upnpClass.contains("audioItem") -> MediaType.AUDIO
                            upnpClass.contains("imageItem") -> MediaType.IMAGE
                            res.endsWith(".mkv") || res.endsWith(".mp4") || res.endsWith(".avi") -> MediaType.VIDEO
                            res.endsWith(".mp3") || res.endsWith(".flac") -> MediaType.AUDIO
                            res.endsWith(".jpg") || res.endsWith(".png") -> MediaType.IMAGE
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
                }
            }
            eventType = parser.next()
        }
    }
}
