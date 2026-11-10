package com.replayer.player

import android.content.Context
import android.net.Uri
import com.replayer.diagnostics.CrashReporter
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class RePlayerEngine(private val context: Context) {
    private val libVlc: LibVLC = LibVLC(
        context,
        arrayListOf("--no-drop-late-frames", "--no-skip-frames", "--audio-time-stretch")
    )
    val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)
    private val _playerState = MutableStateFlow(RePlayerState())
    val playerState: StateFlow<RePlayerState> = _playerState.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null
    private var currentLayout: VLCVideoLayout? = null
    @Volatile
    private var isReleased = false

    init {
        mediaPlayer.setEventListener { event ->
            if (isReleased) return@setEventListener
            try {
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        _playerState.value = _playerState.value.copy(isPlaying = true, isBuffering = false)
                        CrashReporter.breadcrumb("VLC", "Playing: ${_playerState.value.currentMediaTitle}")
                        updateTracks()
                        startProgressTracking()
                    }
                    MediaPlayer.Event.Paused -> {
                        _playerState.value = _playerState.value.copy(isPlaying = false)
                        stopProgressTracking()
                    }
                    MediaPlayer.Event.Stopped, MediaPlayer.Event.EndReached -> {
                        _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false)
                        stopProgressTracking()
                    }
                    MediaPlayer.Event.Buffering -> {
                        _playerState.value = _playerState.value.copy(isBuffering = event.buffering < 100f)
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        val time = event.timeChanged
                        val length = try {
                            if (mediaPlayer.length > 0) mediaPlayer.length else _playerState.value.durationMs
                        } catch (_: Throwable) {
                            _playerState.value.durationMs
                        }
                        _playerState.value = _playerState.value.copy(currentPositionMs = time, durationMs = length)
                    }
                    MediaPlayer.Event.LengthChanged -> {
                        _playerState.value = _playerState.value.copy(durationMs = event.lengthChanged)
                        updateTracks()
                    }
                    MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                        updateTracks()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        CrashReporter.breadcrumb("VLC", "EncounteredError: ${_playerState.value.currentMediaTitle}")
                        _playerState.value = _playerState.value.copy(
                            errorMessage = "Ошибка воспроизведения",
                            isPlaying = false,
                            isBuffering = false
                        )
                        stopProgressTracking()
                    }
                }
            } catch (t: Throwable) {
                CrashReporter.breadcrumb("VLC", "Event listener error: ${t.message}")
            }
        }
    }

    fun attachVideoLayout(layout: VLCVideoLayout) {
        currentLayout = layout
        try {
            if (!mediaPlayer.vlcVout.areViewsAttached()) {
                mediaPlayer.attachViews(layout, null, true, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun play(mediaItem: MediaItem) {
        if (isReleased) return
        CrashReporter.breadcrumb("Player", "play(${mediaItem.sourceType}): ${mediaItem.title}")
        _playerState.value = _playerState.value.copy(currentMediaTitle = mediaItem.title, errorMessage = null)
        if (mediaItem.uri.isBlank()) {
            _playerState.value = _playerState.value.copy(errorMessage = "Пустая ссылка потока", isPlaying = false)
            return
        }
        try {
            val streamUri = Uri.parse(mediaItem.uri)
            val isIptv = mediaItem.sourceType == com.replayer.model.MediaSourceType.IPTV
            val media = Media(libVlc, streamUri).apply {
                setHWDecoderEnabled(true, false)
                if (isIptv) {
                    addOption(":network-caching=3000")
                    addOption(":live-caching=1500")
                    addOption(":http-user-agent=RePlayer-TV")
                } else {
                    addOption(":network-caching=15000")
                    addOption(":file-caching=2000")
                }
            }
            mediaPlayer.media = media
            media.release()

            currentLayout?.let { layout ->
                try {
                    if (!mediaPlayer.vlcVout.areViewsAttached()) {
                        mediaPlayer.attachViews(layout, null, true, false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            mediaPlayer.play()
        } catch (t: Throwable) {
            CrashReporter.breadcrumb("Player", "play exception: ${t.message}")
            _playerState.value = _playerState.value.copy(
                errorMessage = "Ошибка запуска: ${t.localizedMessage ?: "сбой"}",
                isPlaying = false,
                isBuffering = false
            )
        }
    }

    fun togglePlayPause() {
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    fun seekRelative(deltaMs: Long) {
        val duration = if (mediaPlayer.length > 0) mediaPlayer.length else _playerState.value.durationMs
        val target = (mediaPlayer.time + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        mediaPlayer.time = target
        _playerState.value = _playerState.value.copy(currentPositionMs = target)
    }

    fun seekTo(positionMs: Long) {
        val duration = if (mediaPlayer.length > 0) mediaPlayer.length else _playerState.value.durationMs
        val target = positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
        mediaPlayer.time = target
        _playerState.value = _playerState.value.copy(currentPositionMs = target)
    }

    fun selectAudioTrack(track: AudioTrackInfo) {
        // ИСПРАВЛЕНИЕ: Передаем именно ID трека, а не его индекс в массиве
        if (track.index != -1) {
            mediaPlayer.setAudioTrack(track.index)
            _playerState.value = _playerState.value.copy(selectedAudioTrackId = track.id)
            updateTracks()
        }
    }

    fun selectSubtitleTrack(track: SubtitleTrackInfo?) {
        if (track == null) {
            mediaPlayer.setSpuTrack(-1)
            _playerState.value = _playerState.value.copy(selectedSubtitleTrackId = null)
        } else {
            mediaPlayer.setSpuTrack(track.index)
            _playerState.value = _playerState.value.copy(selectedSubtitleTrackId = track.id)
        }
        updateTracks()
    }

    private fun updateTracks() {
        if (isReleased) return
        try {
            val currentAudioId = mediaPlayer.audioTrack
            val audioTracksDesc = mediaPlayer.audioTracks ?: emptyArray()
            val audioList = audioTracksDesc.map { track ->
                AudioTrackInfo(
                    id = track.id.toString(),
                    index = track.id,
                    groupIndex = 0,
                    language = null,
                    label = track.name ?: "Track ${track.id}",
                    codec = null,
                    channelCount = 2,
                    sampleRate = 44100,
                    isSelected = track.id == currentAudioId
                )
            }

            val currentSpuId = mediaPlayer.spuTrack
            val spuTracksDesc = mediaPlayer.spuTracks ?: emptyArray()
            val subList = spuTracksDesc.map { track ->
                SubtitleTrackInfo(
                    id = track.id.toString(),
                    index = track.id,
                    groupIndex = 0,
                    language = null,
                    label = track.name ?: "Subtitle ${track.id}",
                    mimeType = null,
                    isSelected = track.id == currentSpuId
                )
            }

            _playerState.value = _playerState.value.copy(
                audioTracks = audioList,
                subtitleTracks = subList,
                selectedAudioTrackId = if (currentAudioId != -1) currentAudioId.toString() else null,
                selectedSubtitleTrackId = if (currentSpuId != -1) currentSpuId.toString() else null
            )
        } catch (_: Throwable) {}
    }

    private fun startProgressTracking() {
        if (isReleased) return
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && !isReleased) {
                try {
                    val t = mediaPlayer.time
                    val len = mediaPlayer.length
                    if (t >= 0) {
                        _playerState.value = _playerState.value.copy(
                            currentPositionMs = t,
                            durationMs = if (len > 0) len else _playerState.value.durationMs
                        )
                    }
                } catch (_: Throwable) {}
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        CrashReporter.breadcrumb("Player", "release()")
        stopProgressTracking()
        try {
            // ИСПРАВЛЕНИЕ ВЫЛЕТОВ: Сначала отключаем слушатель и отсоединяем вьюху, потом убиваем плеер
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVlc.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
