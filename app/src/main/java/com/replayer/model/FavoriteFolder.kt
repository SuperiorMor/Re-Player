package com.replayer.model

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteFolder(
    val id: String,
    val title: String,
    val sourceType: MediaSourceType,
    val serverControlUrl: String? = null,
    val parentId: String? = null
)
