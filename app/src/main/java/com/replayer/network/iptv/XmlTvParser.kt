package com.replayer.network.iptv

import android.util.Xml
import com.replayer.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

object XmlTvParser {

    private val DATE_FORMAT_TZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val DATE_FORMAT_NO_TZ = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    fun parse(inputStream: InputStream, isGzip: Boolean = false): Map<String, List<EpgProgram>> {
        val stream = if (isGzip) GZIPInputStream(inputStream) else inputStream
        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")

        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        val now = System.currentTimeMillis()
        val minTime = now - 12 * 3600 * 1000L // last 12h
        val maxTime = now + 36 * 3600 * 1000L // next 36h

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")
                val startStr = parser.getAttributeValue(null, "start")
                val stopStr = parser.getAttributeValue(null, "stop")

                if (!channel.isNullOrBlank() && !startStr.isNullOrBlank() && !stopStr.isNullOrBlank()) {
                    val start = parseXmlDate(startStr)
                    val stop = parseXmlDate(stopStr)

                    if (start != null && stop != null && stop >= minTime && start <= maxTime) {
                        var title: String? = null
                        var desc: String? = null

                        // Inner tags
                        while (true) {
                            val innerEvent = parser.next()
                            if (innerEvent == XmlPullParser.START_TAG) {
                                when (parser.name) {
                                    "title" -> title = parser.nextText()
                                    "desc" -> desc = parser.nextText()
                                }
                            } else if (innerEvent == XmlPullParser.END_TAG && parser.name == "programme") {
                                break
                            } else if (innerEvent == XmlPullParser.END_DOCUMENT) {
                                break
                            }
                        }

                        if (!title.isNullOrBlank()) {
                            val list = result.getOrPut(channel) { mutableListOf() }
                            list.add(
                                EpgProgram(
                                    channelId = channel,
                                    title = title,
                                    description = desc,
                                    startTime = start,
                                    endTime = stop
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        // Sort each channel's programs by startTime
        return result.mapValues { (_, progs) -> progs.sortedBy { it.startTime } }
    }

    private fun parseXmlDate(dateStr: String): Long? {
        val trimmed = dateStr.trim()
        return try {
            if (trimmed.length >= 15 && (trimmed.contains("+") || trimmed.contains("-"))) {
                DATE_FORMAT_TZ.parse(trimmed)?.time
            } else {
                val clean = trimmed.take(14)
                DATE_FORMAT_NO_TZ.parse(clean)?.time
            }
        } catch (_: Exception) {
            null
        }
    }
}
