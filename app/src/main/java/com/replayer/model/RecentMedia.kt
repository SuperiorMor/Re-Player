package com.replayer.model

import kotlinx.serialization.Serializable

@Serializable
data class RecentMedia(
    val id: String,
    val title: String,
    val uri: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        title = title,
        uri = uri,
        type = MediaType.VIDEO,
        sourceType = MediaSourceType.UPNP_DLNA
    )
}

