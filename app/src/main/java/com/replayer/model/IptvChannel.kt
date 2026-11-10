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
    val tvgName: String? = null,
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null
) {
    val hasArchive: Boolean
        get() = catchupDays > 0 ||
            (!catchupType.isNullOrBlank() && !catchupType.equals("none", ignoreCase = true) && catchupDays >= 0) ||
            !catchupSource.isNullOrBlank()

    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        title = name,
        uri = streamUrl,
        type = MediaType.VIDEO,
        sourceType = MediaSourceType.IPTV,
        thumbnailUrl = logoUrl
    )
}
