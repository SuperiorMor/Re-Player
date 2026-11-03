package com.replayer.network.iptv

import android.util.Xml
import com.replayer.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

object XmlTvParser {

    fun parse(rawInputStream: InputStream, isGzipHint: Boolean = false): Map<String, List<EpgProgram>> {
        // Detect GZIP by magic bytes (0x1f, 0x8b) or hint
        val buffered = BufferedInputStream(rawInputStream, 65536)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()

        val isGzip = (b1 == 0x1f && b2 == 0x8b) || isGzipHint
        val stream: InputStream = if (isGzip) GZIPInputStream(buffered) else buffered

        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")

        // Mapping from display name / alias (lowercased) -> channel ID
        val aliasToChannelId = mutableMapOf<String, String>()
        // Canonical channel programs: channelId -> list of programs
        val channelPrograms = mutableMapOf<String, MutableList<EpgProgram>>()

        val now = System.currentTimeMillis()
        val minTime = now - 18 * 3600 * 1000L // last 18h
        val maxTime = now + 48 * 3600 * 1000L // next 48h

        var currentChannelId: String? = null
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id")?.trim()
                        }
                        "display-name" -> {
                            if (!currentChannelId.isNullOrBlank()) {
                                val name = parser.nextText().trim()
                                if (name.isNotEmpty()) {
                                    aliasToChannelId[name.lowercase()] = currentChannelId
                                    // Also normalize (remove HD, 4K, spaces)
                                    val clean = normalizeChannelName(name)
                                    if (clean.isNotEmpty()) {
                                        aliasToChannelId[clean] = currentChannelId
                                    }
                                }
                            }
                        }
                        "programme" -> {
                            val channel = parser.getAttributeValue(null, "channel")?.trim()
                            val startStr = parser.getAttributeValue(null, "start")?.trim()
                            val stopStr = parser.getAttributeValue(null, "stop")?.trim()

                            if (!channel.isNullOrBlank() && !startStr.isNullOrBlank() && !stopStr.isNullOrBlank()) {
                                val start = parseXmlDate(startStr)
                                val stop = parseXmlDate(stopStr)

                                if (start != null && stop != null && stop >= minTime && start <= maxTime) {
                                    var title: String? = null
                                    var desc: String? = null

                                    while (true) {
                                        val innerEvent = parser.next()
                                        if (innerEvent == XmlPullParser.START_TAG) {
                                            when (parser.name) {
                                                "title" -> title = parser.nextText().trim()
                                                "desc" -> desc = parser.nextText().trim()
                                            }
                                        } else if (innerEvent == XmlPullParser.END_TAG && parser.name == "programme") {
                                            break
                                        } else if (innerEvent == XmlPullParser.END_DOCUMENT) {
                                            break
                                        }
                                    }

                                    if (!title.isNullOrBlank()) {
                                        val list = channelPrograms.getOrPut(channel) { mutableListOf() }
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
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "channel") {
                        currentChannelId = null
                    }
                }
            }
            eventType = parser.next()
        }

        // Sort canonical lists by startTime
        val sortedCanonical = channelPrograms.mapValues { (_, progs) ->
            progs.sortedBy { it.startTime }
        }

        // Build composite lookup map:
        // 1. All original channel IDs (case-sensitive and lowercase)
        // 2. All display-name aliases pointing to their programs
        val finalMap = mutableMapOf<String, List<EpgProgram>>()

        for ((chId, progs) in sortedCanonical) {
            finalMap[chId] = progs
            finalMap[chId.lowercase()] = progs
            val cleanId = normalizeChannelName(chId)
            if (cleanId.isNotEmpty()) {
                finalMap[cleanId] = progs
            }
        }

        for ((alias, chId) in aliasToChannelId) {
            val progs = sortedCanonical[chId]
            if (progs != null) {
                finalMap.putIfAbsent(alias, progs)
            }
        }

        return finalMap
    }

    private fun normalizeChannelName(raw: String): String {
        return raw.lowercase()
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
            .replace(Regex("\\b(hd|fhd|4k|uhd|sd|50fps|orig|hevc)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ]"), "")
            .trim()
    }

    /**
     * Thread-safe, flexible XMLTV date parser supporting:
     * - "20261103120000 +0300"
     * - "20261103120000 +03:00"
     * - "20261103120000"
     * - "2026-11-03 12:00:00 +0300"
     * - "2026-11-03 12:00:00"
     */
    fun parseXmlDate(dateStr: String): Long? {
        val trimmed = dateStr.trim()
        if (trimmed.length < 14) return null

        return try {
            // Check for timezone offset (+0300, -0500, +03:00)
            val plusIdx = trimmed.indexOf('+')
            val minusIdx = if (plusIdx == -1) trimmed.indexOf('-', 8) else -1
            val tzIdx = if (plusIdx != -1) plusIdx else minusIdx

            if (tzIdx != -1) {
                val datePartRaw = trimmed.substring(0, tzIdx).trim()
                var tzPart = trimmed.substring(tzIdx).trim()
                // Convert +03:00 -> +0300
                if (tzPart.length == 6 && tzPart[3] == ':') {
                    tzPart = tzPart.substring(0, 3) + tzPart.substring(4)
                }

                val cleanDate = datePartRaw.replace("-", "").replace(":", "").replace(" ", "")
                val candidate = if (cleanDate.length >= 14) cleanDate.take(14) + " " + tzPart else null
                if (candidate != null) {
                    val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
                    sdf.parse(candidate)?.time
                } else null
            } else {
                val cleanDate = trimmed.replace("-", "").replace(":", "").replace(" ", "").take(14)
                if (cleanDate.length == 14) {
                    val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    sdf.parse(cleanDate)?.time
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
