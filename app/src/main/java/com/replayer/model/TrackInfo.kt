package com.replayer.model

data class AudioTrackInfo(
    val id: String,
    val index: Int,
    val groupIndex: Int,
    val language: String?,
    val label: String,
    val codec: String?,
    val channelCount: Int,
    val sampleRate: Int,
    val isSelected: Boolean
)

data class SubtitleTrackInfo(
    val id: String,
    val index: Int,
    val groupIndex: Int,
    val language: String?,
    val label: String,
    val mimeType: String?,
    val isSelected: Boolean
)
