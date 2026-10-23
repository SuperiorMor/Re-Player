package com.replayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.extractor.Extractor
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.replayer.model.AudioTrackInfo
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.SubtitleTrackInfo
import com.replayer.network.gdrive.GoogleDriveAuthManager
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    private val renderersFactory = DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        .setEnableDecoderFallback(true)

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs= */ 15_000,
            /* maxBufferMs= */ 50_000,
            /* bufferForPlaybackMs= */ 500,
            /* bufferForPlaybackAfterRebufferMs= */ 1_000
        )
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    private val extractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(false)

    private val baseHttpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(30000)
        .setUserAgent("RePlayer-TV")

    private val defaultDataSourceFactory = DefaultDataSource.Factory(context, baseHttpDataSourceFactory)
    private val defaultMediaSourceFactory = DefaultMediaSourceFactory(defaultDataSourceFactory, extractorsFactory)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(defaultMediaSourceFactory)
        .setSeekBackIncrementMs(10000)
        .setSeekForwardIncrementMs(10000)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .build().apply {
            volume = 1.0f
        }

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
                val exoDuration = exoPlayer.duration
                val duration = if (exoDuration > 0) {
                    exoDuration
                } else {
                    _playerState.value.durationMs
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

        // Ensure container MIME type is explicitly specified (crucial for AVI and DLNA streams)
        // Check file extension FIRST because DLNA servers often misreport AVI as video/mpeg
        val detectedMimeType = when {
            mediaItem.title.endsWith(".avi", ignoreCase = true) || mediaItem.uri.endsWith(".avi", ignoreCase = true) -> MimeTypes.VIDEO_AVI
            mediaItem.title.endsWith(".mkv", ignoreCase = true) || mediaItem.uri.endsWith(".mkv", ignoreCase = true) -> MimeTypes.VIDEO_MATROSKA
            mediaItem.title.endsWith(".mp4", ignoreCase = true) || mediaItem.uri.endsWith(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
            mediaItem.title.endsWith(".mov", ignoreCase = true) || mediaItem.uri.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            mediaItem.title.endsWith(".ts", ignoreCase = true) || mediaItem.uri.endsWith(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
            mediaItem.title.endsWith(".mp3", ignoreCase = true) || mediaItem.uri.endsWith(".mp3", ignoreCase = true) -> MimeTypes.AUDIO_MPEG
            mediaItem.mimeType?.isNotBlank() == true && mediaItem.mimeType != "application/octet-stream" -> mediaItem.mimeType
            else -> null
        }
        if (detectedMimeType != null) {
            mediaBuilder.setMimeType(detectedMimeType)
        }

        _playerState.value = _playerState.value.copy(
            currentMediaTitle = mediaItem.title,
            errorMessage = null
        )

        val finalHeaders = headers.toMutableMap()
        if (mediaItem.sourceType == MediaSourceType.GOOGLE_DRIVE) {
            val token = GoogleDriveAuthManager(context).getSavedAccessToken()
            if (!token.isNullOrBlank()) {
                finalHeaders["Authorization"] = "Bearer $token"
            }
        }

        val isAvi = mediaItem.title.endsWith(".avi", ignoreCase = true) ||
                mediaItem.uri.endsWith(".avi", ignoreCase = true) ||
                mediaItem.format?.equals("avi", ignoreCase = true) == true
        val activeExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                if (isAvi) {
                    return arrayOf(
                        com.replayer.player.avi.AviExtractor(),
                        *DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(false).createExtractors()
                    )
                }
                return DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(false).createExtractors()
            }

            override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
                if (isAvi) {
                    val cleanHeaders = responseHeaders.filterKeys { !it.equals("Content-Type", ignoreCase = true) }
                    val defaultExtractors = DefaultExtractorsFactory()
                        .setConstantBitrateSeekingEnabled(false)
                        .createExtractors(uri, cleanHeaders)
                    return arrayOf(com.replayer.player.avi.AviExtractor(), *defaultExtractors)
                }
                return DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(false)
                    .createExtractors(uri, responseHeaders)
            }
        }

        val exoMediaItem = mediaBuilder.build()
        val dataSourceFactory = if (finalHeaders.isNotEmpty()) {
            val customHttpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setUserAgent("RePlayer-TV")
                .setDefaultRequestProperties(finalHeaders)
            DefaultDataSource.Factory(context, customHttpFactory)
        } else {
            defaultDataSourceFactory
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, activeExtractorsFactory)
        exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(exoMediaItem))

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
        val duration = if (exoPlayer.duration > 0) exoPlayer.duration else _playerState.value.durationMs
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0, duration.coerceAtLeast(0L))
        exoPlayer.seekTo(target)
        _playerState.value = _playerState.value.copy(currentPositionMs = target)
    }

    fun seekTo(positionMs: Long) {
        val duration = if (exoPlayer.duration > 0) exoPlayer.duration else _playerState.value.durationMs
        val target = positionMs.coerceIn(0, duration.coerceAtLeast(0L))
        exoPlayer.seekTo(target)
        _playerState.value = _playerState.value.copy(currentPositionMs = target)
    }

    fun selectAudioTrack(track: AudioTrackInfo) {
        val tracks = exoPlayer.currentTracks
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_AUDIO && groupIndex == track.groupIndex) {
                val trackGroup = group.mediaTrackGroup
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
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
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _playerState.value = _playerState.value.copy(selectedSubtitleTrackId = null)
        } else {
            val tracks = exoPlayer.currentTracks
            for (groupIndex in 0 until tracks.groups.size) {
                val group = tracks.groups[groupIndex]
                if (group.type == C.TRACK_TYPE_TEXT && groupIndex == track.groupIndex) {
                    val trackGroup = group.mediaTrackGroup
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
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
