package com.replayer.network.upnp

import android.util.Log
import android.util.Xml
import com.replayer.model.UpnpDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URL
import java.util.concurrent.TimeUnit

object UpnpDeviceParser {
    private const val TAG = "UpnpDeviceParser"
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAndParse(locationUrl: String): UpnpDevice? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(locationUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null

            val parser = Xml.newPullParser()
            parser.setInput(StringReader(body))

            var friendlyName = "DLNA Media Server"
            var manufacturer = "Unknown"
            var udn = locationUrl
            var contentDirectoryControlUrl: String? = null
            var insideContentDirectory = false
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "service") {
                            insideContentDirectory = false
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "friendlyName" -> friendlyName = text
                                "manufacturer" -> manufacturer = text
                                "UDN" -> udn = text
                                "serviceType" -> {
                                    if (text.contains("ContentDirectory")) {
                                        insideContentDirectory = true
                                    }
                                }
                                "controlURL" -> {
                                    if (insideContentDirectory && contentDirectoryControlUrl == null) {
                                        contentDirectoryControlUrl = resolveUrl(locationUrl, text)
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "service") {
                            insideContentDirectory = false
                        }
                    }
                }
                eventType = parser.next()
            }

            UpnpDevice(
                id = udn,
                friendlyName = friendlyName,
                manufacturer = manufacturer,
                location = locationUrl,
                contentDirectoryControlUrl = contentDirectoryControlUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device description for $locationUrl", e)
            null
        }
    }

    fun resolveUrl(baseUrl: String, path: String): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            val base = URL(baseUrl)
            URL(base, path).toString()
        }
    }
}
