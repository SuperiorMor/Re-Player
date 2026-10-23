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
    val format: String? = null
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
        if (!format.isNullOrEmpty()) return format.uppercase()
        val ext = title.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.length <= 5) ext.uppercase() else type.name
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
