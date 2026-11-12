package com.replayer.network.upnp

import android.util.Log
import android.util.Xml
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.MediaType
import com.replayer.model.resolveMediaContainerFormat
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

    private data class SoapDidlResponse(
        val didlXml: String,
        val numberReturned: Int,
        val totalMatches: Int
    )

    companion object {
        private val UNESCAPED_AMP_REGEX = Regex("&(?!amp;|lt;|gt;|quot;|apos;|#\\d+;|#x[0-9a-fA-F]+;)")
        private val RESULT_FALLBACK_REGEX = Regex("<(?:[^:>\\s]+:)?Result[^>]*>(.*?)</(?:[^:>\\s]+:)?Result>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val NUM_RETURNED_REGEX = Regex("<(?:[^:>\\s]+:)?NumberReturned[^>]*>(\\d+)</", RegexOption.IGNORE_CASE)
        private val TOTAL_MATCHES_REGEX = Regex("<(?:[^:>\\s]+:)?TotalMatches[^>]*>(\\d+)</", RegexOption.IGNORE_CASE)
    }

    suspend fun browse(controlUrl: String, objectId: String = "0"): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val pageSize = 200
        var startingIndex = 0
        var totalMatches = Int.MAX_VALUE
        val safeMaxItems = 2000

        try {
            while (items.size < totalMatches && items.size < safeMaxItems) {
                val soapAction = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
                val soapBody = buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                    append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
                    append("<s:Body>")
                    append("<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">")
                    append("<ObjectID>${escapeXmlText(objectId)}</ObjectID>")
                    append("<BrowseFlag>BrowseDirectChildren</BrowseFlag>")
                    append("<Filter>*</Filter>")
                    append("<StartingIndex>$startingIndex</StartingIndex>")
                    append("<RequestedCount>$pageSize</RequestedCount>")
                    append("<SortCriteria></SortCriteria>")
                    append("</u:Browse>")
                    append("</s:Body>")
                    append("</s:Envelope>")
                }

                val pageResp = executeSoapDidlRequest(controlUrl, soapAction, soapBody) ?: break
                val beforeCount = items.size
                parseDidlLite(pageResp.didlXml, items, objectId)
                val parsedThisPage = items.size - beforeCount

                if (pageResp.totalMatches > 0) {
                    totalMatches = pageResp.totalMatches
                }
                val advanced = maxOf(pageResp.numberReturned, parsedThisPage)
                if (advanced <= 0 || parsedThisPage == 0) break
                startingIndex += advanced
                if (pageResp.totalMatches <= 0 && parsedThisPage < pageSize) break
            }
        } catch (e: Exception) {
            Log.e("ContentDirectoryService", "Browse error for $controlUrl (obj=$objectId)", e)
        }

        items
    }

    suspend fun search(
        controlUrl: String,
        containerId: String = "0",
        searchCriteria: String,
        maxItems: Int = 200
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        try {
            val soapAction = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Search\""
            val soapBody = buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
                append("<s:Body>")
                append("<u:Search xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">")
                append("<ContainerID>${escapeXmlText(containerId)}</ContainerID>")
                append("<SearchCriteria>${escapeXmlText(searchCriteria)}</SearchCriteria>")
                append("<Filter>*</Filter>")
                append("<StartingIndex>0</StartingIndex>")
                append("<RequestedCount>$maxItems</RequestedCount>")
                append("<SortCriteria></SortCriteria>")
                append("</u:Search>")
                append("</s:Body>")
                append("</s:Envelope>")
            }

            val resp = executeSoapDidlRequest(controlUrl, soapAction, soapBody)
            if (resp != null) {
                parseDidlLite(resp.didlXml, items, containerId)
            }
        } catch (e: Exception) {
            Log.w("ContentDirectoryService", "Search not supported or failed on $controlUrl: ${e.message}")
        }
        items
    }

    private fun executeSoapDidlRequest(
        controlUrl: String,
        soapAction: String,
        soapBody: String
    ): SoapDidlResponse? {
        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPAction", soapAction)
            .post(soapBody.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val rawXml = response.body?.string() ?: return null
            return extractSoapResponse(rawXml)
        }
    }

    private fun escapeXmlText(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun extractSoapResponse(soapXml: String): SoapDidlResponse? {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(soapXml))
            var didl: String? = null
            var numReturned = 0
            var totalMatches = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val localTag = (parser.name ?: "").substringAfterLast(':').lowercase()
                    when (localTag) {
                        "result" -> didl = parser.nextText()
                        "numberreturned" -> numReturned = parser.nextText().trim().toIntOrNull() ?: 0
                        "totalmatches" -> totalMatches = parser.nextText().trim().toIntOrNull() ?: 0
                    }
                }
                event = parser.next()
            }
            if (!didl.isNullOrBlank()) {
                return SoapDidlResponse(didl, numReturned, totalMatches)
            }
        } catch (_: Throwable) {
            // Fallback to regex extraction if outer SOAP envelope is malformed
        }

        val resultMatch = RESULT_FALLBACK_REGEX.find(soapXml) ?: return null
        val encoded = resultMatch.groupValues[1]
        val didl = encoded
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        val numRet = NUM_RETURNED_REGEX.find(soapXml)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val totMat = TOTAL_MATCHES_REGEX.find(soapXml)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return SoapDidlResponse(didl, numRet, totMat)
    }

    private fun parseDidlLite(didlXml: String, items: MutableList<MediaItem>, defaultParentId: String) {
        val sanitizedXml = didlXml.replace(UNESCAPED_AMP_REGEX, "&amp;")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(sanitizedXml))

        var eventType = parser.eventType
        var currentItem: MutableMap<String, String>? = null
        var currentResIsThumb = false
        var currentResProto = ""
        val textBuffer = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val rawName = parser.name ?: ""
                    val localTag = rawName.substringAfterLast(':').lowercase()
                    textBuffer.clear()

                    if (localTag == "container" || localTag == "item") {
                        currentItem = mutableMapOf()
                        currentResIsThumb = false
                        currentResProto = ""
                        currentItem["isContainer"] = if (localTag == "container") "true" else "false"
                        currentItem["id"] = parser.getAttributeValue(null, "id") ?: ""
                        val attrParent = parser.getAttributeValue(null, "parentID")
                        currentItem["parentID"] = if (!attrParent.isNullOrBlank()) attrParent else defaultParentId
                    }
                    if (localTag == "res" && currentItem != null) {
                        val proto = parser.getAttributeValue(null, "protocolInfo") ?: ""
                        val sizeAttr = parser.getAttributeValue(null, "size")
                        val resAttr = parser.getAttributeValue(null, "resolution")
                        val upnpCls = currentItem["class"] ?: ""
                        currentResProto = proto
                        currentResIsThumb = proto.contains("JPEG_TN", ignoreCase = true) ||
                            proto.contains("PNG_TN", ignoreCase = true) ||
                            (upnpCls.contains("videoItem", ignoreCase = true) && proto.contains("image/", ignoreCase = true))

                        if (!currentResIsThumb) {
                            if (proto.isNotBlank() && (!currentItem.containsKey("protocolInfo") || !proto.contains("image/", ignoreCase = true))) {
                                currentItem["protocolInfo"] = proto
                            }
                            if (!sizeAttr.isNullOrEmpty() && !currentItem.containsKey("size")) {
                                currentItem["size"] = sizeAttr
                            }
                            if (!resAttr.isNullOrEmpty() && (!currentItem.containsKey("resolution") || !proto.contains("image/", ignoreCase = true))) {
                                currentItem["resolution"] = resAttr
                            }
                        } else if (!resAttr.isNullOrEmpty() && !currentItem.containsKey("albumArtResolution")) {
                            currentItem["albumArtResolution"] = resAttr
                        }
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
                            "res" -> {
                                val existingProto = currentItem["resProto"] ?: ""
                                if (currentResIsThumb) {
                                    if (!currentItem.containsKey("albumArt")) {
                                        currentItem["albumArt"] = text
                                    }
                                } else if (!currentItem.containsKey("res")) {
                                    currentItem["res"] = text
                                    currentItem["resProto"] = currentResProto
                                } else if (existingProto.contains("image/", ignoreCase = true) &&
                                    (currentResProto.contains("video/", ignoreCase = true) || currentResProto.contains("audio/", ignoreCase = true))
                                ) {
                                    // Prefer primary video/audio stream over thumbnail if <res> appeared before <upnp:class>
                                    if (!currentItem.containsKey("albumArt")) {
                                        currentItem["albumArt"] = currentItem["res"] ?: ""
                                    }
                                    currentItem["res"] = text
                                    currentItem["resProto"] = currentResProto
                                }
                            }
                            "class" -> currentItem["class"] = text
                            "date" -> currentItem["date"] = text
                            "albumarturi" -> if (!currentItem.containsKey("albumArt")) currentItem["albumArt"] = text
                        }
                    }

                    if ((localTag == "container" || localTag == "item") && currentItem != null) {
                        val isContainer = currentItem["isContainer"] == "true"
                        val upnpClass = currentItem["class"] ?: ""
                        val title = currentItem["title"]?.trim()?.ifEmpty { "Unnamed" } ?: "Unnamed"
                        val id = currentItem["id"] ?: ""
                        val itemParentId = currentItem["parentID"] ?: defaultParentId
                        val rawRes = currentItem["res"] ?: ""
                        val size = currentItem["size"]?.toLongOrNull() ?: 0L
                        val date = currentItem["date"]
                        val albumArt = currentItem["albumArt"]
                        val resolution = currentItem["resolution"]
                        val proto = currentItem["protocolInfo"] ?: currentItem["resProto"] ?: ""
                        val res = rawRes.ifBlank { if (!isContainer) (albumArt ?: "") else "" }
                        val resClean = res.substringBefore('?').substringBefore('#')
                        val titleLower = title.lowercase()
                        val resLower = resClean.lowercase()
                        fun hasAnyExt(vararg exts: String): Boolean =
                            exts.any { titleLower.endsWith(it) || resLower.endsWith(it) }

                        val mediaType = when {
                            isContainer -> MediaType.DIRECTORY
                            upnpClass.contains("imageItem", ignoreCase = true) ||
                                (!upnpClass.contains("videoItem", ignoreCase = true) &&
                                    !upnpClass.contains("audioItem", ignoreCase = true) &&
                                    (proto.contains("image/", ignoreCase = true) ||
                                        hasAnyExt(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"))) -> MediaType.IMAGE
                            hasAnyExt(".srt", ".ass", ".ssa", ".sub", ".vtt", ".idx") ||
                                proto.contains("text/", ignoreCase = true) -> MediaType.UNKNOWN
                            upnpClass.contains("audioItem", ignoreCase = true) ||
                                proto.contains("audio/", ignoreCase = true) ||
                                hasAnyExt(".mp3", ".flac", ".m4a", ".wav", ".aac", ".ogg", ".opus", ".wma") -> MediaType.AUDIO
                            upnpClass.contains("videoItem", ignoreCase = true) ||
                                proto.contains("video/", ignoreCase = true) ||
                                hasAnyExt(".mkv", ".mp4", ".avi", ".ts", ".m2ts", ".mov", ".webm", ".m4v", ".wmv", ".mpg", ".mpeg", ".flv") -> MediaType.VIDEO
                            else -> MediaType.UNKNOWN
                        }

                        val resolvedFormat = resolveMediaContainerFormat(
                            title = title,
                            uri = res,
                            mimeOrProto = proto,
                            type = mediaType
                        )

                        items.add(
                            MediaItem(
                                id = id,
                                title = title,
                                uri = res,
                                type = mediaType,
                                sourceType = MediaSourceType.UPNP_DLNA,
                                size = size,
                                mimeType = proto.ifBlank { null },
                                thumbnailUrl = albumArt,
                                parentId = itemParentId,
                                dateAdded = date,
                                format = resolvedFormat,
                                resolution = resolution
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
