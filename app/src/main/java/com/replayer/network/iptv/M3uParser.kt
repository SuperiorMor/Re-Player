package com.replayer.network.iptv

import com.replayer.model.IptvChannel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class M3uParseResult(
    val channels: List<IptvChannel>,
    val epgUrl: String?
)

object M3uParser {

    private fun extractAttr(line: String, vararg attrNames: String): String? {
        val pattern = attrNames.joinToString("|") { Regex.escape(it) }
        val regex = Regex(
            """(?:^|[\s,])(?:$pattern)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]*))""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(line) ?: return null
        val value = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?: match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
        return value?.trim()?.ifBlank { null }
    }

    /**
     * Finds the channel title after the first comma outside quotes in #EXTINF line.
     */
    private fun extractTitle(extInf: String): String? {
        var inSingleQuote = false
        var inDoubleQuote = false
        val colonIdx = extInf.indexOf(':')
        val startIdx = if (colonIdx != -1) colonIdx + 1 else 0

        for (i in startIdx until extInf.length) {
            val c = extInf[i]
            when {
                c == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                c == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                c == ',' && !inSingleQuote && !inDoubleQuote -> {
                    val rawTitle = extInf.substring(i + 1).trim()
                    return rawTitle.ifBlank { null }
                }
            }
        }
        return null
    }

    fun parse(inputStream: InputStream): M3uParseResult {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val channels = mutableListOf<IptvChannel>()
        val usedIds = mutableSetOf<String>()
        var detectedEpgUrl: String? = null

        var globalCatchupType: String? = null
        var globalCatchupDays: Int? = null
        var globalCatchupSource: String? = null

        var currentExtInf: String? = null
        var perItemGroup: String? = null
        var perItemLogo: String? = null
        var sectionGroup: String? = null
        var index = 0

        reader.forEachLine { rawLine ->
            // Strip UTF-8 BOM if present
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEachLine

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    extractAttr(line, "url-tvg", "x-tvg-url")?.let { raw ->
                        val firstUrl = raw.split(",", ";").firstOrNull()?.trim()
                        if (!firstUrl.isNullOrBlank()) {
                            detectedEpgUrl = firstUrl
                        }
                    }
                    extractAttr(line, "catchup", "catchup-type")?.let { globalCatchupType = it.lowercase() }
                    extractAttr(line, "tvg-rec", "catchup-days", "timeshift")?.toIntOrNull()?.let {
                        globalCatchupDays = it.coerceAtLeast(0)
                    }
                    extractAttr(line, "catchup-source")?.let { globalCatchupSource = it }
                }
                line.startsWith("#EXTINF:", ignoreCase = true) || line.equals("#EXTINF", ignoreCase = true) -> {
                    currentExtInf = line
                    perItemGroup = null
                    perItemLogo = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val grp = line.substringAfter(":", "").trim().ifBlank { null }
                    if (currentExtInf != null) {
                        perItemGroup = grp
                    } else {
                        sectionGroup = grp
                    }
                }
                line.startsWith("#EXTIMG:", ignoreCase = true) ||
                    line.startsWith("#EXTALBUMARTURL:", ignoreCase = true) -> {
                    val img = line.substringAfter(":", "").trim().ifBlank { null }
                    if (currentExtInf != null) {
                        perItemLogo = img
                    }
                }
                !line.startsWith("#") -> {
                    val extInf = currentExtInf
                    val streamUrl = line

                    val tvgId = extInf?.let { extractAttr(it, "tvg-id", "channel-id") }
                    val tvgName = extInf?.let { extractAttr(it, "tvg-name") }
                    val extInfLogo = extInf?.let { extractAttr(it, "tvg-logo", "tvg-icon", "logo") }
                    val extInfGroup = extInf?.let { extractAttr(it, "group-title", "tvg-group", "group") }

                    val itemCatchupType = extInf?.let { extractAttr(it, "catchup", "catchup-type") }?.lowercase()
                    val itemDays = extInf?.let { extractAttr(it, "tvg-rec", "catchup-days", "timeshift") }?.toIntOrNull()
                    val itemCatchupSource = extInf?.let { extractAttr(it, "catchup-source") }

                    val (finalCatchupType, finalCatchupDays, finalCatchupSource) = when {
                        itemDays == 0 || itemCatchupType == "none" -> Triple(null, 0, null)
                        else -> {
                            val type = itemCatchupType ?: globalCatchupType
                            val src = itemCatchupSource ?: globalCatchupSource
                            val days = (itemDays ?: globalCatchupDays ?: if (type != null || src != null) 3 else 0)
                                .coerceIn(0, 14)
                            Triple(type, days, src)
                        }
                    }

                    val title = extInf?.let { extractTitle(it) } ?: tvgName ?: "Канал ${index + 1}"
                    val groupTitle = (extInfGroup ?: perItemGroup ?: sectionGroup)?.trim()?.ifBlank { null } ?: "Общие"
                    val finalLogo = (extInfLogo ?: perItemLogo)?.trim()?.ifBlank { null }

                    if (title.isNotBlank() && streamUrl.isNotBlank()) {
                        index++
                        val baseId = tvgId ?: "ch_${index}_${title.hashCode()}"
                        val uniqueId = if (usedIds.add(baseId)) {
                            baseId
                        } else {
                            val disambiguated = "${baseId}_$index"
                            usedIds.add(disambiguated)
                            disambiguated
                        }

                        channels.add(
                            IptvChannel(
                                id = uniqueId,
                                name = title,
                                streamUrl = streamUrl,
                                logoUrl = finalLogo,
                                group = groupTitle,
                                tvgId = tvgId,
                                tvgName = tvgName,
                                catchupType = finalCatchupType,
                                catchupDays = finalCatchupDays,
                                catchupSource = finalCatchupSource
                            )
                        )
                    }

                    currentExtInf = null
                    perItemGroup = null
                    perItemLogo = null
                }
            }
        }

        return M3uParseResult(channels = channels, epgUrl = detectedEpgUrl)
    }
}
