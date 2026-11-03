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

    private val TVG_ID_REGEX = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val TVG_NAME_REGEX = Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val TVG_LOGO_REGEX = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val GROUP_TITLE_REGEX = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val URL_TVG_REGEX = Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""", RegexOption.IGNORE_CASE)

    fun parse(inputStream: InputStream): M3uParseResult {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val channels = mutableListOf<IptvChannel>()
        var detectedEpgUrl: String? = null

        var currentExtInf: String? = null
        var index = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine

            if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                URL_TVG_REGEX.find(line)?.groupValues?.getOrNull(1)?.let {
                    val firstUrl = it.split(",", ";").firstOrNull()?.trim()
                    if (!firstUrl.isNullOrBlank()) {
                        detectedEpgUrl = firstUrl
                    }
                }
            } else if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                currentExtInf = line
            } else if (!line.startsWith("#")) {
                // This is the stream URL
                val extInf = currentExtInf
                val streamUrl = line

                val tvgId = extInf?.let { TVG_ID_REGEX.find(it)?.groupValues?.getOrNull(1)?.trim() }
                val tvgName = extInf?.let { TVG_NAME_REGEX.find(it)?.groupValues?.getOrNull(1)?.trim() }
                val logoUrl = extInf?.let { TVG_LOGO_REGEX.find(it)?.groupValues?.getOrNull(1)?.trim() }
                val groupTitle = extInf?.let { GROUP_TITLE_REGEX.find(it)?.groupValues?.getOrNull(1)?.trim() }
                    ?.ifBlank { null } ?: "Общие"

                // Extract title after last comma
                val commaIdx = extInf?.lastIndexOf(',') ?: -1
                val title = if (commaIdx != -1 && extInf != null && commaIdx + 1 < extInf.length) {
                    extInf.substring(commaIdx + 1).trim()
                } else {
                    tvgName ?: "Канал ${index + 1}"
                }

                if (title.isNotBlank() && streamUrl.isNotBlank()) {
                    index++
                    val channelId = tvgId?.ifBlank { null } ?: "ch_${index}_${title.hashCode()}"
                    channels.add(
                        IptvChannel(
                            id = channelId,
                            name = title,
                            streamUrl = streamUrl,
                            logoUrl = logoUrl?.ifBlank { null },
                            group = groupTitle,
                            tvgId = tvgId?.ifBlank { null },
                            tvgName = tvgName?.ifBlank { null }
                        )
                    )
                }
                currentExtInf = null
            }
        }

        return M3uParseResult(channels = channels, epgUrl = detectedEpgUrl)
    }
}
