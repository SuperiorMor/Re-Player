package com.replayer.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val uri: String,
    val type: MediaType,
    val sourceType: MediaSourceType,
    val size: Long = 0L,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val thumbnailUrl: String? = null,
    val parentId: String? = null,
    val dateAdded: String? = null,
    val format: String? = null,
    val resolution: String? = null
) {
    fun formattedSize(): String {
        if (type == MediaType.DIRECTORY || size <= 0L) return ""
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f ГБ", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f МБ", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f КБ", kb)
            else -> "$size Б"
        }
    }

    fun fileFormat(): String {
        if (type == MediaType.DIRECTORY) return "ПАПКА"
        return resolveMediaContainerFormat(
            title = title,
            uri = uri,
            mimeOrProto = mimeType,
            type = type,
            storedFormat = format
        )
    }

    fun formattedDate(): String {
        if (dateAdded.isNullOrEmpty()) return ""
        // If it's ISO like 2023-05-12T14:30:00 -> take 2023-05-12
        return if (dateAdded.contains('T')) {
            dateAdded.substringBefore('T')
        } else {
            dateAdded.take(10)
        }
    }
}

private val KNOWN_MEDIA_CONTAINERS = setOf(
    "mkv", "mp4", "avi", "ts", "m2ts", "mts", "mov", "webm", "m4v", "wmv",
    "mpg", "mpeg", "flv", "vob", "3gp", "ogv", "divx", "asf", "iso",
    "mp3", "flac", "m4a", "wav", "aac", "ogg", "opus", "wma", "ac3", "dts",
    "jpg", "jpeg", "png", "webp", "bmp", "gif"
)

private fun normalizeContainerLabel(ext: String): String = when (val low = ext.lowercase()) {
    "jpeg" -> "JPG"
    "mpg" -> "MPEG"
    else -> low.uppercase()
}

fun resolveMediaContainerFormat(
    title: String,
    uri: String? = null,
    mimeOrProto: String? = null,
    type: MediaType = MediaType.UNKNOWN,
    storedFormat: String? = null
): String {
    if (type == MediaType.DIRECTORY) return "ПАПКА"

    // 1. Check file extension in actual stream URL (<res>) first — most DLNA servers keep true container here
    val uriFile = uri
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.substringAfterLast('/', "")
        .orEmpty()
    val uriExt = uriFile.substringAfterLast('.', "").lowercase()
    if (uriExt in KNOWN_MEDIA_CONTAINERS) {
        return normalizeContainerLabel(uriExt)
    }

    // 2. Check stored format only if it is an actual known media container (rejects "REMUX", "AVC", "264")
    val storedExt = storedFormat?.trim()?.lowercase().orEmpty()
    if (storedExt in KNOWN_MEDIA_CONTAINERS) {
        return normalizeContainerLabel(storedExt)
    }

    // 3. Check <dc:title> suffix only if it is a known container extension
    val titleExt = title.trim().substringAfterLast('.', "").lowercase()
    if (titleExt in KNOWN_MEDIA_CONTAINERS) {
        return normalizeContainerLabel(titleExt)
    }

    // 4. Inspect DLNA protocolInfo / MIME type (e.g. "http-get:*:video/x-matroska:DLNA.ORG_OP=01")
    val proto = mimeOrProto?.lowercase().orEmpty()
    if (proto.isNotEmpty()) {
        when {
            proto.contains("matroska") || proto.contains("x-mkv") -> return "MKV"
            proto.contains("video/mp4") || proto.contains("mp4v") -> return "MP4"
            proto.contains("msvideo") || proto.contains("video/avi") || proto.contains("divx") -> return "AVI"
            proto.contains("mp2t") || proto.contains("mpeg-tts") -> return "TS"
            proto.contains("quicktime") -> return "MOV"
            proto.contains("webm") -> return "WEBM"
            proto.contains("x-ms-wmv") || proto.contains("wmv") -> return "WMV"
            proto.contains("x-flv") -> return "FLV"
            proto.contains("video/mpeg") -> return "MPEG"
            proto.contains("flac") -> return "FLAC"
            proto.contains("audio/mpeg") || proto.contains("audio/mp3") -> return "MP3"
            proto.contains("audio/mp4") || proto.contains("audio/aac") -> return "M4A"
            proto.contains("audio/wav") || proto.contains("x-wav") -> return "WAV"
            proto.contains("audio/ogg") || proto.contains("opus") -> return "OGG"
            proto.contains("image/jpeg") -> return "JPG"
            proto.contains("image/png") -> return "PNG"
            proto.contains("image/webp") -> return "WEBP"
        }
    }

    return when (type) {
        MediaType.VIDEO -> "VIDEO"
        MediaType.AUDIO -> "AUDIO"
        MediaType.IMAGE -> "IMAGE"
        else -> type.name
    }
}
