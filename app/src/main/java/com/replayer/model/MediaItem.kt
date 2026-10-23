package com.replayer.model

import kotlinx.serialization.Serializable

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
    val parentId: String? = null
)
