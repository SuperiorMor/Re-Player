package com.replayer.model

import kotlinx.serialization.Serializable

@Serializable
data class IptvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String = "Все",
    val tvgId: String? = null,
    val tvgName: String? = null
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        title = name,
        uri = streamUrl,
        type = MediaType.VIDEO,
        sourceType = MediaSourceType.IPTV,
        thumbnailUrl = logoUrl
    )
}

