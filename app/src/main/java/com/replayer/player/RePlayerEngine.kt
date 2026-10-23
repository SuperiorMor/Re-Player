package com.replayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.replayer.model.AudioTrackInfo
import com.replayer.model.MediaItem
import com.replayer.model.SubtitleTrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class RePlayerEngine(private val context: Context) {

    private val trackSelector = DefaultTrackSelector(context)
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setSeekBackIncrementMs(10000)
        .setSeekForwardIncrementMs(10000)
        .build()

    private val _playerState = MutableStateFlow(RePlayerState())
    val playerState: StateFlow<RePlayerState> = _playerState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                if (isPlaying) startProgressTracking() else stopProgressTracking()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val isBuffering = playbackState == Player.STATE_BUFFERING
                val duration = if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    exoPlayer.duration.coerceAtLeast(0L)
                } else {
                    0L
                }
                _playerState.value = _playerState.value.copy(
                    isBuffering = isBuffering,
                    durationMs = duration
                )
            }

            override fun onTracksChanged(tracks: Tracks) {
                extractTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                _playerState.value = _playerState.value.copy(
                    errorMessage = error.localizedMessage ?: "Playback error",
                    isPlaying = false,
                    isBuffering = false
                )
            }
        })
    }

    fun play(mediaItem: MediaItem, headers: Map<String, String> = emptyMap()) {
        val mediaBuilder = ExoMediaItem.Builder()
            .setUri(mediaItem.uri)
            .setMediaId(mediaItem.id)

        _playerState.value = _playerState.value.copy(
            currentMediaTitle = mediaItem.title,
            errorMessage = null
        )

        exoPlayer.setMediaItem(mediaBuilder.build())
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    fun seekRelative(deltaMs: Long) {
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0, exoPlayer.duration.coerceAtLeast(0L))
        exoPlayer.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceIn(0, exoPlayer.duration.coerceAtLeast(0L)))
    }

    fun selectAudioTrack(track: AudioTrackInfo) {
        val tracks = exoPlayer.currentTracks
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_AUDIO && groupIndex == track.groupIndex) {
                val trackGroup = group.mediaTrackGroup
                trackSelector.parameters = trackSelector.buildUponParameters()
                    .setOverrideForType(TrackSelectionOverride(trackGroup, listOf(track.index)))
                    .build()
                _playerState.value = _playerState.value.copy(selectedAudioTrackId = track.id)
                break
            }
        }
    }

    fun selectSubtitleTrack(track: SubtitleTrackInfo?) {
        if (track == null) {
            // Disable subtitles
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _playerState.value = _playerState.value.copy(selectedSubtitleTrackId = null)
        } else {
            val tracks = exoPlayer.currentTracks
            for (groupIndex in 0 until tracks.groups.size) {
                val group = tracks.groups[groupIndex]
                if (group.type == C.TRACK_TYPE_TEXT && groupIndex == track.groupIndex) {
                    val trackGroup = group.mediaTrackGroup
                    trackSelector.parameters = trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(trackGroup, listOf(track.index)))
                        .build()
                    _playerState.value = _playerState.value.copy(selectedSubtitleTrackId = track.id)
                    break
                }
            }
        }
    }

    private fun extractTracks(tracks: Tracks) {
        val audioList = mutableListOf<AudioTrackInfo>()
        val subList = mutableListOf<SubtitleTrackInfo>()
        var selectedAudioId: String? = null
        var selectedSubId: String? = null

        for (gIdx in 0 until tracks.groups.size) {
            val group = tracks.groups[gIdx]
            val trackGroup = group.mediaTrackGroup

            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (tIdx in 0 until group.length) {
                        val format = trackGroup.getFormat(tIdx)
                        val id = "audio_${gIdx}_${tIdx}"
                        val isSelected = group.isTrackSelected(tIdx)
                        if (isSelected) selectedAudioId = id

                        val lang = format.language ?: "und"
                        val channels = format.channelCount
                        val codec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AUDIO"
                        val label = format.label ?: "Track ${tIdx + 1} ($lang $channels.ch $codec)"

                        audioList.add(
                            AudioTrackInfo(
                                id = id,
                                index = tIdx,
                                groupIndex = gIdx,
                                language = format.language,
                                label = label,
                                codec = codec,
                                channelCount = channels,
                                sampleRate = format.sampleRate,
                                isSelected = isSelected
                            )
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (tIdx in 0 until group.length) {
                        val format = trackGroup.getFormat(tIdx)
                        val id = "sub_${gIdx}_${tIdx}"
                        val isSelected = group.isTrackSelected(tIdx)
                        if (isSelected) selectedSubId = id

                        val lang = format.language ?: "und"
                        val label = format.label ?: "Subtitle ${tIdx + 1} ($lang)"

                        subList.add(
                            SubtitleTrackInfo(
                                id = id,
                                index = tIdx,
                                groupIndex = gIdx,
                                language = format.language,
                                label = label,
                                mimeType = format.sampleMimeType,
                                isSelected = isSelected
                            )
                        )
                    }
                }
            }
        }

        _playerState.value = _playerState.value.copy(
            audioTracks = audioList,
            subtitleTracks = subList,
            selectedAudioTrackId = selectedAudioId,
            selectedSubtitleTrackId = selectedSubId
        )
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                _playerState.value = _playerState.value.copy(
                    currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                )
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressTracking()
        exoPlayer.release()
    }
}
