package com.replayer.model

import kotlinx.serialization.Serializable

@Serializable
data class RecentMedia(
    val id: String,
    val title: String,
    val uri: String,
    val timestamp: Long = System.currentTimeMillis(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    fun progress(): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        title = title,
        uri = uri,
        type = MediaType.VIDEO,
        sourceType = MediaSourceType.UPNP_DLNA
    )
}
