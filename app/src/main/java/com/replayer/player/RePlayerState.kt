package com.replayer.player

import com.replayer.model.AudioTrackInfo
import com.replayer.model.SubtitleTrackInfo

data class RePlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentMediaTitle: String = "",
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val errorMessage: String? = null
)
