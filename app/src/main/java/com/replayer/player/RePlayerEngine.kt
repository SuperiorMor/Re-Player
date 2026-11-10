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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class RePlayerEngine(private val context: Context) {
    private val libVlc: LibVLC = LibVLC(
        context,
        arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
            "--ipv4-timeout=5000",
            "--http-reconnect"
        )
    )
    private val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)
    private val _playerState = MutableStateFlow(RePlayerState())
    val playerState: StateFlow<RePlayerState> = _playerState.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val engineExecutor = Executors.newSingleThreadExecutor()
    private val playRequestSeq = AtomicLong(0L)
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
                        _playerState.value = _playerState.value.copy(currentPositionMs = time)
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
        if (isReleased) return
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
        val reqId = playRequestSeq.incrementAndGet()
        stopProgressTracking()
        _playerState.value = _playerState.value.copy(
            currentMediaTitle = mediaItem.title,
            errorMessage = null,
            isBuffering = true
        )
        if (mediaItem.uri.isBlank()) {
            _playerState.value = _playerState.value.copy(
                errorMessage = "Пустая ссылка потока",
                isPlaying = false,
                isBuffering = false
            )
            return
        }

        // Attach surface on UI thread without waiting for native stream teardown
        currentLayout?.let { layout ->
            try {
                if (!mediaPlayer.vlcVout.areViewsAttached()) {
                    mediaPlayer.attachViews(layout, null, true, false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            engineExecutor.execute {
                if (isReleased || reqId != playRequestSeq.get()) return@execute
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
                    // mediaPlayer.media = ... calls libvlc input_Close (vlc_join); must run off the main thread!
                    mediaPlayer.media = media
                    media.release()

                    if (!isReleased && reqId == playRequestSeq.get()) {
                        mediaPlayer.play()
                    }
                } catch (t: Throwable) {
                    if (!isReleased && reqId == playRequestSeq.get()) {
                        CrashReporter.breadcrumb("Player", "play exception: ${t.message}")
                        _playerState.value = _playerState.value.copy(
                            errorMessage = "Ошибка запуска: ${t.localizedMessage ?: "сбой"}",
                            isPlaying = false,
                            isBuffering = false
                        )
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun togglePlayPause() {
        if (isReleased) return
        try {
            engineExecutor.execute {
                if (isReleased) return@execute
                try {
                    if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun seekRelative(deltaMs: Long) {
        if (isReleased) return
        try {
            engineExecutor.execute {
                if (isReleased) return@execute
                try {
                    val duration = if (mediaPlayer.length > 0) mediaPlayer.length else _playerState.value.durationMs
                    val rawTarget = (mediaPlayer.time + deltaMs).coerceAtLeast(0L)
                    val target = if (duration > 0L) rawTarget.coerceAtMost(duration) else rawTarget
                    mediaPlayer.time = target
                    _playerState.value = _playerState.value.copy(currentPositionMs = target)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun seekTo(positionMs: Long) {
        if (isReleased) return
        try {
            engineExecutor.execute {
                if (isReleased) return@execute
                try {
                    val duration = if (mediaPlayer.length > 0) mediaPlayer.length else _playerState.value.durationMs
                    val rawTarget = positionMs.coerceAtLeast(0L)
                    val target = if (duration > 0L) rawTarget.coerceAtMost(duration) else rawTarget
                    mediaPlayer.time = target
                    _playerState.value = _playerState.value.copy(currentPositionMs = target)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun selectAudioTrack(track: AudioTrackInfo) {
        if (isReleased || track.index == -1) return
        _playerState.value = _playerState.value.copy(selectedAudioTrackId = track.id)
        try {
            engineExecutor.execute {
                if (isReleased) return@execute
                try {
                    mediaPlayer.setAudioTrack(track.index)
                    updateTracks()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun selectSubtitleTrack(track: SubtitleTrackInfo?) {
        if (isReleased) return
        _playerState.value = _playerState.value.copy(selectedSubtitleTrackId = track?.id)
        try {
            engineExecutor.execute {
                if (isReleased) return@execute
                try {
                    if (track == null) {
                        mediaPlayer.setSpuTrack(-1)
                    } else {
                        mediaPlayer.setSpuTrack(track.index)
                    }
                    updateTracks()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
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
        playRequestSeq.incrementAndGet()
        CrashReporter.breadcrumb("Player", "release()")
        stopProgressTracking()

        // Detach UI listeners and surface synchronously so Android View cleanup never deadlocks,
        // then run blocking libvlc_media_player_stop/release on background executor to prevent ANR.
        try {
            mediaPlayer.setEventListener(null)
        } catch (_: Throwable) {}
        try {
            if (mediaPlayer.vlcVout.areViewsAttached()) {
                mediaPlayer.detachViews()
            }
        } catch (_: Throwable) {}

        try {
            engineExecutor.execute {
                try { mediaPlayer.stop() } catch (_: Throwable) {}
                try { mediaPlayer.release() } catch (_: Throwable) {}
                try { libVlc.release() } catch (_: Throwable) {}
            }
            engineExecutor.shutdown()
        } catch (_: Throwable) {}
    }
}
