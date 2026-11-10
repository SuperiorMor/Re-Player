package com.replayer.network.iptv

import android.util.Xml
import com.replayer.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

data class XmlTvParseResult(
    val programs: Map<String, List<EpgProgram>>,
    val logos: Map<String, String>
)

object XmlTvParser {

    fun parse(
        rawInputStream: InputStream,
        targetChannels: Set<String>? = null,
        archiveChannels: Set<String>? = null,
        onProgress: ((String) -> Unit)? = null
    ): XmlTvParseResult {
        // Detect GZIP by magic bytes (0x1f, 0x8b)
        val buffered = BufferedInputStream(rawInputStream, 65536)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()

        val isGzip = (b1 == 0x1f && b2 == 0x8b)
        val stream: InputStream = if (isGzip) GZIPInputStream(buffered) else buffered

        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")

        // Mapping from display name / alias (lowercased) -> channel XML ID
        val aliasToChannelId = mutableMapOf<String, String>()
        // All XML IDs that matched the user's target channels (or all channels if targetChannels is null)
        val relevantXmlChannelIds = mutableSetOf<String>()
        // XML IDs that support catchup archive (load -3 days only for these channels!)
        val archiveXmlChannelIds = mutableSetOf<String>()
        // Channel logos from <icon src="..."> inside <channel>
        val channelIcons = mutableMapOf<String, String>()
        // Canonical channel programs: channelId -> list of programs
        val channelPrograms = mutableMapOf<String, MutableList<EpgProgram>>()

        val now = System.currentTimeMillis()
        val archiveMinTime = now - 3L * 24 * 3600 * 1000L // last 3 days ONLY for archive channels
        val liveMinTime = now - 2L * 3600 * 1000L // last 2h for non-archive channels
        val maxTime = now + 36L * 3600 * 1000L // next 36h

        var currentChannelId: String? = null
        var totalProgrammesCount = 0
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id")?.trim()
                            if (!currentChannelId.isNullOrBlank()) {
                                val idLower = currentChannelId.lowercase()
                                val idClean = normalizeChannelName(currentChannelId)
                                if (targetChannels == null) {
                                    relevantXmlChannelIds.add(currentChannelId)
                                } else if (targetChannels.contains(currentChannelId) ||
                                    targetChannels.contains(idLower) ||
                                    (idClean.isNotEmpty() && targetChannels.contains(idClean))
                                ) {
                                    relevantXmlChannelIds.add(currentChannelId)
                                }

                                if (archiveChannels == null ||
                                    archiveChannels.contains(currentChannelId) ||
                                    archiveChannels.contains(idLower) ||
                                    (idClean.isNotEmpty() && archiveChannels.contains(idClean))
                                ) {
                                    archiveXmlChannelIds.add(currentChannelId)
                                }
                            }
                        }
                        "display-name" -> {
                            if (!currentChannelId.isNullOrBlank()) {
                                val name = parser.nextText().trim()
                                if (name.isNotEmpty()) {
                                    val lower = name.lowercase()
                                    aliasToChannelId[lower] = currentChannelId
                                    val clean = normalizeChannelName(name)
                                    if (clean.isNotEmpty()) {
                                        aliasToChannelId[clean] = currentChannelId
                                    }

                                    // Check if this display-name matches any target channel
                                    if (targetChannels != null) {
                                        if (targetChannels.contains(lower) ||
                                            targetChannels.contains(name) ||
                                            (clean.isNotEmpty() && targetChannels.contains(clean))
                                        ) {
                                            relevantXmlChannelIds.add(currentChannelId)
                                        }
                                    }
                                    // Check if this display-name matches an archive-capable channel
                                    if (archiveChannels != null) {
                                        if (archiveChannels.contains(lower) ||
                                            archiveChannels.contains(name) ||
                                            (clean.isNotEmpty() && archiveChannels.contains(clean))
                                        ) {
                                            archiveXmlChannelIds.add(currentChannelId)
                                        }
                                    }
                                }
                            }
                        }
                        "icon" -> {
                            if (!currentChannelId.isNullOrBlank()) {
                                val src = parser.getAttributeValue(null, "src")?.trim()
                                if (!src.isNullOrBlank() && !channelIcons.containsKey(currentChannelId)) {
                                    channelIcons[currentChannelId] = src
                                }
                            }
                        }
                        "programme" -> {
                            val channel = parser.getAttributeValue(null, "channel")?.trim()
                            val startStr = parser.getAttributeValue(null, "start")?.trim()
                            val stopStr = parser.getAttributeValue(null, "stop")?.trim()

                            // Check if this programme belongs to a matched relevant channel
                            val isRelevant = if (targetChannels != null) {
                                !channel.isNullOrBlank() && relevantXmlChannelIds.contains(channel)
                            } else {
                                !channel.isNullOrBlank()
                            }

                            if (!isRelevant || channel.isNullOrBlank() || startStr.isNullOrBlank() || stopStr.isNullOrBlank()) {
                                skipTag(parser)
                            } else {
                                val start = parseXmlDate(startStr)
                                val stop = parseXmlDate(stopStr)
                                val effectiveMinTime = if (archiveChannels == null || archiveXmlChannelIds.contains(channel)) {
                                    archiveMinTime
                                } else {
                                    liveMinTime
                                }

                                if (start != null && stop != null && stop >= effectiveMinTime && start <= maxTime) {
                                    var title: String? = null
                                    var desc: String? = null

                                    while (true) {
                                        val innerEvent = parser.next()
                                        if (innerEvent == XmlPullParser.START_TAG) {
                                            when (parser.name) {
                                                "title" -> title = parser.nextText().trim()
                                                "desc" -> desc = parser.nextText().trim()
                                                else -> skipTag(parser)
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
                                                description = desc?.take(250),
                                                startTime = start,
                                                endTime = stop
                                            )
                                        )
                                        totalProgrammesCount++
                                        if (totalProgrammesCount % 500 == 0) {
                                            onProgress?.invoke("Индексация: $totalProgrammesCount передач...")
                                        }
                                    }
                                } else {
                                    skipTag(parser)
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
            if (progs != null && progs.isNotEmpty()) {
                finalMap.putIfAbsent(alias, progs)
                val clean = normalizeChannelName(alias)
                if (clean.isNotEmpty()) {
                    finalMap.putIfAbsent(clean, progs)
                }
            }
        }

        val finalLogos = mutableMapOf<String, String>()
        for ((chId, logo) in channelIcons) {
            if (targetChannels == null || relevantXmlChannelIds.contains(chId)) {
                finalLogos[chId] = logo
                finalLogos[chId.lowercase()] = logo
                val cleanId = normalizeChannelName(chId)
                if (cleanId.isNotEmpty()) {
                    finalLogos[cleanId] = logo
                }
            }
        }
        for ((alias, chId) in aliasToChannelId) {
            if (targetChannels == null || relevantXmlChannelIds.contains(chId)) {
                val logo = channelIcons[chId]
                if (!logo.isNullOrBlank()) {
                    finalLogos.putIfAbsent(alias, logo)
                    val clean = normalizeChannelName(alias)
                    if (clean.isNotEmpty()) {
                        finalLogos.putIfAbsent(clean, logo)
                    }
                }
            }
        }

        return XmlTvParseResult(programs = finalMap, logos = finalLogos)
    }

    private fun skipTag(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    fun normalizeChannelName(raw: String): String {
        return raw.lowercase()
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
            .replace(Regex("\\b(hd|fhd|4k|uhd|sd|50fps|orig|hevc)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ]"), "")
            .trim()
    }

    /**
     * Blazing fast, thread-safe XMLTV date parser without SimpleDateFormat allocations:
     * - "20261103120000 +0300"
     * - "20261103120000 +03:00"
     * - "20261103120000"
     */
    fun parseXmlDate(dateStr: String): Long? {
        val s = dateStr.trim()
        if (s.length < 14) return null
        return try {
            val y = s.substring(0, 4).toInt()
            val m = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val min = s.substring(10, 12).toInt()
            val sec = s.substring(12, 14).toInt()

            var offsetMinutes = 0
            val rest = s.substring(14).trim()
            if (rest.isNotEmpty() && (rest.startsWith("+") || rest.startsWith("-"))) {
                val sign = if (rest[0] == '+') 1 else -1
                val cleanTz = rest.substring(1).replace(":", "")
                if (cleanTz.length >= 4) {
                    val tzH = cleanTz.substring(0, 2).toInt()
                    val tzM = cleanTz.substring(2, 4).toInt()
                    offsetMinutes = sign * (tzH * 60 + tzM)
                }
            }

            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.set(y, m - 1, d, h, min, sec)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis - (offsetMinutes * 60 * 1000L)
        } catch (_: Exception) {
            null
        }
    }
}
